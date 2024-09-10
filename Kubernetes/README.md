# Creating Highly Available Kubernetes Cluster with an external etcd cluster

1. [Kubernetes Installation](#kubernetes-installation)
2. [Load Balancer](#create-load-balancer-for-kube-apiserver)
    - [keepalived](#keepalived-configuration)
    - [haproxy](#haproxy-configuration)
3. [External etcd cluster](#external-etcd-cluster)
4. [Create cluster](#create-kubernetes-cluster)
    - [First control-plane node](#set-up-the-first-control-plane-node)
    - [Rest of the control-plane nodes](#steps-for-the-rest-of-the-control-plane-nodes)
    - [Worker nodes](#install-workers)
    - [Verify configuration](#verify-configuration)
    - [Deploy application](#deploy-the-application-to-kubernetes)
5. [Troubleshooting](#troubleshooting)

### External etcd Topology

In an HA cluster with an external etcd topology, the etcd cluster is separate from the control plane nodes. Each control plane node runs instances of `kube-apiserver`, `kube-scheduler` and `kube-controller-manager`, with the kube-apiserver exposed to worker nodes via a load balancer. The etcd members, running on separate hosts, communicate with each control plane node's kube-apiserver.

This setup decouples the control plane from the etcd members, enhancing redundancy. Losing a control plane instance or an etcd member has less impact compared to a stacked topology. However, it requires more hosts—at least three for control plane nodes and three for etcd nodes.

![Highly Available Kubernetes Cluster Topology](/Kubernetes/HighlyAvailableCluster/HAtopology.png)

## Kubernetes installation

1. Configure required modules

```bash
cat <<EOF | sudo tee /etc/modules-load.d/k8s.conf
overlay
br_netfilter
EOF

sudo modprobe overlay
sudo modprobe br_netfilter
```

2. Configure required sysctl to persist across system reboots

```bash
cat <<EOF | sudo tee /etc/sysctl.d/k8s.conf
net.bridge.bridge-nf-call-iptables  = 1
net.bridge.bridge-nf-call-ip6tables = 1
net.ipv4.ip_forward                 = 1
EOF

# Apply sysctl parameters without rebooting to the current running environment
sudo sysctl --system
# Verify that net.ipv4.ip_forward is set to 1
sysctl net.ipv4.ip_forward
```

3. Containered installation from Docker distro

```bash
sudo apt-get update
sudo apt-get install ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

# Add the repository to Apt sources:
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt-get update
sudo apt-get install -y containerd
sudo mkdir -p /etc/containerd
sudo containerd config default | sudo tee /etc/containerd/config.toml
```
Go to /etc/containerd/config.toml. 
Find:
 ```bash
 [plugins."io.containerd.grpc.v1.cri".containerd.runtimes.runc]
  ...
 [plugins."io.containerd.grpc.v1.cri".containerd.runtimes.runc.options]
    # And Set SystemdCgroup = true    
    SystemdCgroup = true
 ```
 You need CRI support enabled to use containerd with Kubernetes.Make sure that cri is not included in thedisabled_plugins list within /etc/containerd/config.toml
```bash
sudo cat /etc/containerd/config.toml | grep "disabled_plugins"
```
And then:
```bash
sudo systemctl restart containerd
sudo systemctl enable containerd
sudo systemctl status containerd
```

4. Installing Kubernetes packages

```bash
sudo apt-get update
sudo apt-get install -y apt-transport-https ca-certificates curl gpg

# If the folder `/etc/apt/keyrings` does not exist, it should be created before the curl command, read the note below.
# sudo mkdir -p -m 755 /etc/apt/keyrings
curl -fsSL https://pkgs.k8s.io/core:/stable:/v1.30/deb/Release.key | sudo gpg --dearmor -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg

# This overwrites any existing configuration in /etc/apt/sources.list.d/kubernetes.list
echo 'deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] https://pkgs.k8s.io/core:/stable:/v1.30/deb/ /' | sudo tee /etc/apt/sources.list.d/kubernetes.list
# sudo chmod 644 /etc/apt/sources.list.d/kubernetes.list   # helps tools such as command-not-found to work correctly

sudo apt-get update
sudo apt-get install -y kubelet kubeadm kubectl
sudo apt-mark hold kubelet kubeadm kubectl
sudo systemctl enable --now kubelet
```

5. Check required ports 

These `required ports` need to be open in order for Kubernetes components to communicate with each other. 

**Control-plane**:

| Protocol | Direction | Port Range | Purpose                   | Used By                |
|----------|-----------|------------|---------------------------|------------------------|
| TCP      | Inbound   | 6443       | Kubernetes API server     | All                    |
| TCP      | Inbound   | 2379-2380  | etcd server client API    | kube-apiserver, etcd   |
| TCP      | Inbound   | 10250      | Kubelet API               | Self, Control plane    |
| TCP      | Inbound   | 10259      | kube-scheduler            | Self                   |
| TCP      | Inbound   | 10257      | kube-controller-manager   | Self                   |

**Worker nodes**:

| Protocol | Direction | Port Range     | Purpose            | Used By              |
|----------|-----------|----------------|--------------------|----------------------|
| TCP      | Inbound   | 10250          | Kubelet API        | Self, Control plane  |
| TCP      | Inbound   | 10256          | kube-proxy         | Self, Load balancers |
| TCP      | Inbound   | 30000-32767    | NodePort Services  | All                  |

The pod network plugin you use may also require certain ports to be open.

You can use tools like `netcat` to check if a port is open. For example:
```bash
nc 127.0.0.1 6443 -v
```

If the port isn't open, you can allow it through the firewall using `ufw`:
```bash
sudo ufw allow 6443
nc 127.0.0.1 6443 -v
```

6. Disable Swap:

You **must** disable swap if the kubelet is not properly configured to use swap.
```bash
sudo swapoff -a
sudo sed -i '/ swap / s/^\(.*\)$/#\1/g' /etc/fstab
```

## Create load balancer for kube-apiserver 

When setting up a cluster with more than one control plane, higher availability can be achieved by putting the API Server instances behind a load balancer. 

- The `keepalived` service provides a virtual IP address managed by a configurable health check
- The `haproxy` service can be configured for simple stream-based load balancing thus allowing TLS termination to be handled by the API Server instances behind it.

### keepalived configuration

Install `keepalived` :
```bash
sudo apt -y install keepalived
```

Create two files in `/etc/keepalived` directory: the service configuration file and health check script. 

**/etc/keepalived/keepalived.conf**

```bash
    global_defs {
        router_id LVS_DEVEL
    }
    vrrp_script check_apiserver {
    script "/etc/keepalived/check_apiserver.sh"
    interval 3
    weight -2
    fall 10
    rise 2
    }

    vrrp_instance VI_1 {
        state MASTER            # MASTER (on primary node) or BACKUP (on backup node) 
        interface enp0s3        # network interface that virtual IP address is assigned
        virtual_router_id 51    # ROUTER_ID same for all keepalived cluster hosts
        priority 101            # set priority : [Master] > [BACKUP]
        authentication {
            auth_type PASS      
            auth_pass 42        # same for all keepalived cluster hosts
        }
        unicast_src_ip 192.168.1.1
        virtual_ipaddress {
            192.168.1.10        # virtual IP address negotiated between the keepalived cluster hosts
        }
        track_script {
            check_apiserver
        }
    }
```
The above keepalived configuration uses health check script **/etc/keepalived/check_apiserver.sh**
```bash
    #!/bin/sh

    errorExit() {
        echo "*** $*" 1>&2
        exit 1
    }

    curl -sfk --max-time 2 https://localhost:${APISERVER_DEST_PORT}/healthz -o /dev/null || errorExit "Error GET https://localhost:${APISERVER_DEST_PORT}/healthz"
```

You can then configure Backup Node.

### haproxy configuration

Install HAProxy:
```bash
 sudo apt -y install haproxy
```

Modify service configuration file in **/etc/haproxy/haproxy.cfg**:

```bash
global
        log /dev/log    local0
        log /dev/log    local1 notice
        chroot /var/lib/haproxy
        stats socket /run/haproxy/admin.sock mode 660 level admin
        stats timeout 30s
        user haproxy
        group haproxy
        log stdout format raw local0
        daemon

defaults
        log                     global
        mode                    http
        option                  httplog
        option                  dontlognull
        option http-server-close
        option forwardfor       except 127.0.0.0/8
        option                  redispatch
        retries                 1
        timeout http-request    10s
        timeout queue           20s
        timeout connect         5s
        timeout client          35s
        timeout server          35s
        timeout http-keep-alive 10s
        timeout check           10s
        errorfile 400 /etc/haproxy/errors/400.http
        errorfile 403 /etc/haproxy/errors/403.http
        errorfile 408 /etc/haproxy/errors/408.http
        errorfile 500 /etc/haproxy/errors/500.http
        errorfile 502 /etc/haproxy/errors/502.http
        errorfile 503 /etc/haproxy/errors/503.http
        errorfile 504 /etc/haproxy/errors/504.http

#---------------------------------------------------------------------
# apiserver frontend which proxys to the control plane nodes
#---------------------------------------------------------------------
frontend apiserver
    bind *:6443     # APISERVER_DEST_PORT
    mode tcp
    option tcplog
    default_backend apiserverbackend

#---------------------------------------------------------------------
# round robin balancing for apiserver
#---------------------------------------------------------------------
backend apiserverbackend
    option httpchk

    http-check connect ssl
    http-check send meth GET uri /healthz
    http-check expect status 200

    mode tcp
    balance     roundrobin

    # add control-plane nodes
    server ${HOST1_ID} ${HOST1_ADDRESS}:${APISERVER_SRC_PORT} check verify none
    server server-master1 192.168.1.11:6443 check verify none 
    server server-master2 192.168.1.12:6443 check verify none
    server server-master3 192.168.1.13:6443 check verify none
```

Run the services:
```bash
systemctl enable haproxy --now
systemctl enable keepalived --now
``` 

Test the connection: 
```bash
nc -v <LOAD_BALANCER_IP> <PORT>
```
A connection refused error is expected because the API server is not yet running.

## External etcd cluster

The general approach is to generate all certs on one node and only distribute the necessary files to the other nodes.

1. Configure the kubelet to be a service manager for etcd. Do this on every host where etcd should be running.

**/etc/systemd/system/kubelet.service.d/kubelet.conf**
```bash
# Replace "systemd" with the cgroup driver of your container runtime. The default value in the kubelet is "cgroupfs".
# Replace the value of "containerRuntimeEndpoint" for a different container runtime if needed.
apiVersion: kubelet.config.k8s.io/v1beta1
kind: KubeletConfiguration
authentication:
  anonymous:
    enabled: false
  webhook:
    enabled: false
authorization:
  mode: AlwaysAllow
cgroupDriver: systemd
address: 127.0.0.1
containerRuntimeEndpoint: unix:///var/run/containerd/containerd.sock
staticPodPath: /etc/kubernetes/manifests
```

**/etc/systemd/system/kubelet.service.d/20-etcd-service-manager.conf**
```bash
[Service]
ExecStart=
ExecStart=/usr/bin/kubelet --config=/etc/systemd/system/kubelet.service.d/kubelet.conf
Restart=always
```

Then:

```bash
systemctl daemon-reload
systemctl restart kubelet
```

Check the kubelet status to ensure it is running:
```bash
systemctl status kubelet
```

2. Create configuration files for kubeadm. Do it only on one host.

```bash
# Update HOST0, HOST1 and HOST2 with the IPs of your hosts
export HOST0=192.168.1.101
export HOST1=192.168.1.102
export HOST2=192.168.1.103

# Update NAME0, NAME1 and NAME2 with the hostnames of your hosts
export NAME0="etcd-server1"
export NAME1="etcd-server2"
export NAME2="etcd-server3"

# Create temp directories to store files that will end up on other hosts
mkdir -p /tmp/${HOST0}/ /tmp/${HOST1}/ /tmp/${HOST2}/

HOSTS=(${HOST0} ${HOST1} ${HOST2})
NAMES=(${NAME0} ${NAME1} ${NAME2})

for i in "${!HOSTS[@]}"; do
HOST=${HOSTS[$i]}
NAME=${NAMES[$i]}
cat << EOF > /tmp/${HOST}/kubeadmcfg.yaml
---
apiVersion: "kubeadm.k8s.io/v1beta3"
kind: InitConfiguration
nodeRegistration:
    name: ${NAME}
localAPIEndpoint:
    advertiseAddress: ${HOST}
---
apiVersion: "kubeadm.k8s.io/v1beta3"
kind: ClusterConfiguration
etcd:
    local:
        serverCertSANs:
        - "${HOST}"
        peerCertSANs:
        - "${HOST}"
        extraArgs:
            initial-cluster: ${NAMES[0]}=https://${HOSTS[0]}:2380,${NAMES[1]}=https://${HOSTS[1]}:2380,${NAMES[2]}=https://${HOSTS[2]}:2380
            initial-cluster-state: new
            name: ${NAME}
            listen-peer-urls: https://${HOST}:2380
            listen-client-urls: https://${HOST}:2379
            advertise-client-urls: https://${HOST}:2379
            initial-advertise-peer-urls: https://${HOST}:2380
EOF
done
```

3. Generate the certificate authority

Run this command on host where you generated the configuration files for kubeadm:

```bash
kubeadm init phase certs etcd-ca
```

This creates two files:

```bash
/etc/kubernetes/pki/etcd/ca.crt
/etc/kubernetes/pki/etcd/ca.key
```

4. Create certificates for each member:

```bash
kubeadm init phase certs etcd-server --config=/tmp/${HOST2}/kubeadmcfg.yaml
kubeadm init phase certs etcd-peer --config=/tmp/${HOST2}/kubeadmcfg.yaml
kubeadm init phase certs etcd-healthcheck-client --config=/tmp/${HOST2}/kubeadmcfg.yaml
kubeadm init phase certs apiserver-etcd-client --config=/tmp/${HOST2}/kubeadmcfg.yaml
cp -R /etc/kubernetes/pki /tmp/${HOST2}/
# cleanup non-reusable certificates
find /etc/kubernetes/pki -not -name ca.crt -not -name ca.key -type f -delete

kubeadm init phase certs etcd-server --config=/tmp/${HOST1}/kubeadmcfg.yaml
kubeadm init phase certs etcd-peer --config=/tmp/${HOST1}/kubeadmcfg.yaml
kubeadm init phase certs etcd-healthcheck-client --config=/tmp/${HOST1}/kubeadmcfg.yaml
kubeadm init phase certs apiserver-etcd-client --config=/tmp/${HOST1}/kubeadmcfg.yaml
cp -R /etc/kubernetes/pki /tmp/${HOST1}/
find /etc/kubernetes/pki -not -name ca.crt -not -name ca.key -type f -delete

kubeadm init phase certs etcd-server --config=/tmp/${HOST0}/kubeadmcfg.yaml
kubeadm init phase certs etcd-peer --config=/tmp/${HOST0}/kubeadmcfg.yaml
kubeadm init phase certs etcd-healthcheck-client --config=/tmp/${HOST0}/kubeadmcfg.yaml
kubeadm init phase certs apiserver-etcd-client --config=/tmp/${HOST0}/kubeadmcfg.yaml
# No need to move the certs because they are for HOST0

# clean up certs that should not be copied off this host
find /tmp/${HOST2} -name ca.key -type f -delete
find /tmp/${HOST1} -name ca.key -type f -delete
```

5. Copy certificates and kubeadm configs.

```bash
USER=username
HOST=${HOST1} # HOST1 and HOST2
scp -r /tmp/${HOST}/* ${USER}@${HOST}:
ssh ${USER}@${HOST}
USER@HOST $ sudo -Es
root@HOST $ chown -R root:root pki
root@HOST $ mv pki /etc/kubernetes/
```

6. Ensure all expected files exist

The complete list of required files on `$HOST0` is:
```
/tmp/${HOST0}
└── kubeadmcfg.yaml
---
/etc/kubernetes/pki
├── apiserver-etcd-client.crt
├── apiserver-etcd-client.key
└── etcd
    ├── ca.crt
    ├── ca.key
    ├── healthcheck-client.crt
    ├── healthcheck-client.key
    ├── peer.crt
    ├── peer.key
    ├── server.crt
    └── server.key
```
On `$HOST1` and `$HOST2`:
```
$HOME
└── kubeadmcfg.yaml
---
/etc/kubernetes/pki
├── apiserver-etcd-client.crt
├── apiserver-etcd-client.key
└── etcd
    ├── ca.crt
    ├── healthcheck-client.crt
    ├── healthcheck-client.key
    ├── peer.crt
    ├── peer.key
    ├── server.crt
    └── server.key
```

7. Create the static pod manifests.

On each host run the kubeadm command to generate a static manifest for etcd.

```bash
root@HOST0 $ kubeadm init phase etcd local --config=/tmp/${HOST0}/kubeadmcfg.yaml
root@HOST1 $ kubeadm init phase etcd local --config=$HOME/kubeadmcfg.yaml
root@HOST2 $ kubeadm init phase etcd local --config=$HOME/kubeadmcfg.yaml
```

8. Copy the following files from any etcd node in the cluster to the first control plane node:

```bash
export CONTROL_PLANE="user@host"
scp /etc/kubernetes/pki/etcd/ca.crt "${CONTROL_PLANE}":
scp /etc/kubernetes/pki/apiserver-etcd-client.crt "${CONTROL_PLANE}":
scp /etc/kubernetes/pki/apiserver-etcd-client.key "${CONTROL_PLANE}":
```
And move them to the right directories:

## Create kubernetes cluster 

### Set up the first control plane node 

1. Edit **/etc/hosts** file on each control plane node :
You need to ensure that each control plane node can resolve the hostnames of other nodes and etcd servers.
```bash
127.0.0.1 localhost
127.0.1.1 server-master1
192.168.1.1 server1
192.168.1.101 etcd-server1
192.168.1.102 etcd-server2
192.168.1.103 etcd-server3
192.168.1.12 server-master2
192.168.1.13 server-master3
[...]
```

2. Create file **kubeadm-config.yaml**:

```bash
kind: InitConfiguration
apiVersion: kubeadm.k8s.io/v1beta3
nodeRegistration:
  name: "server-master1"
  criSocket: "unix:///var/run/containerd/containerd.sock"
localAPIEndpoint:
  advertiseAddress: "192.168.1.11" ##first control-plane node ipaddress
  bindPort: 6443
---
apiVersion: kubeadm.k8s.io/v1beta3
kind: ClusterConfiguration
kubernetesVersion: stable
controlPlaneEndpoint: "LOAD_BALANCER_DNS:LOAD_BALANCER_PORT" # use virtual IP address generated by keepalived
networking:
  podSubnet: "10.244.0.0/16" # for flannel
clusterName: "HA-cluster"
etcd:
  external:
    endpoints:
      - https://etcd-server1:2379  # https://<serverID or serverIP>:<PORT>
      - https://etcd-server2:2379
      - https://etcd-server3:2379
    caFile: /etc/kubernetes/pki/etcd/ca.crt
    certFile: /etc/kubernetes/pki/apiserver-etcd-client.crt
    keyFile: /etc/kubernetes/pki/apiserver-etcd-client.key
---
apiVersion: kubelet.config.k8s.io/v1beta1
kind: KubeletConfiguration
cgroupDriver: systemd
serverTLSBootstrap: true
```

3. Run `sudo kubeadm init --config kubeadm-config.yaml --upload-certs` on this node

```bash
Your Kubernetes control-plane has initialized successfully!

To start using your cluster, you need to run the following as a regular user:

  mkdir -p $HOME/.kube
  sudo cp -i /etc/kubernetes/admin.conf $HOME/.kube/config
  sudo chown $(id -u):$(id -g) $HOME/.kube/config

Alternatively, if you are the root user, you can run:

  export KUBECONFIG=/etc/kubernetes/admin.conf

You should now deploy a pod network to the cluster.
Run "kubectl apply -f [podnetwork].yaml" with one of the options listed at:
  https://kubernetes.io/docs/concepts/cluster-administration/addons/

You can now join any number of the control-plane node running the following command on each as root:

  kubeadm join <control-plane-host>:<control-plane-port> --token <token> \
        --discovery-token-ca-cert-hash sha256:<hash> \
        --control-plane --certificate-key <key>

Please note that the certificate-key gives access to cluster sensitive data, keep it secret!
As a safeguard, uploaded-certs will be deleted in two hours; If necessary, you can use
"kubeadm init phase upload-certs --upload-certs" to reload certs afterward.

Then you can join any number of worker nodes by running the following on each as root:

kubeadm join <control-plane-host>:<control-plane-port> --token <token> \
        --discovery-token-ca-cert-hash sha256:<hash>
```

Write the output join commands that are returned to a text file for later use.


4. Setup Container Network Interface (CNI) - Flannel:

```bash
kubectl apply -f https://github.com/flannel-io/flannel/releases/latest/download/kube-flannel.yml
```

### Steps for the rest of the control plane nodes 

- Make sure the first control plane node is fully initialized.
- Join each control plane node with the join command you saved to a text file. It's recommended to join the control plane nodes one at a time.
```bash
kubeadm join <control-plane-host>:<control-plane-port> --token <token> --discovery-token-ca-cert-hash sha256:<hash> --control-plane --certificate-key <key>
```
- Don't forget that the decryption key from --certificate-key expires after two hours, by default.

### Install workers 

- Worker nodes can be joined to the cluster with the command you stored previously as the output from the kubeadm init command:
```bash
kubeadm join <control-plane-host>:<control-plane-port> --token <token> --discovery-token-ca-cert-hash sha256:<hash>
```

### Verify configuration

The cluster is set up incorrectly, unless every pod is running.

```bash
kubectl get nodes
kubectl get nodes -o wide
kubectl get pods -A
kubectl get pods -o wide
kubectl cluster-info
```

### Deploy the application to Kubernetes

Create a Kubernetes deployment and service for the application.
**deployment.yaml**
```bash
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web-app
spec:
  replicas: 3
  selector:
    matchLabels:
      app: web-app
  template:
    metadata:
      labels:
        app: web-app
    spec:
      containers:
      - name: web-app
        image: dockerhub-username/app:1.2
        ports:
        - containerPort: 8080
        readinessProbe:
          httpGet:
            path: /
            port: 8080
          initialDelaySeconds: 5
          periodSeconds: 10
```

**service.yaml**
```bash
apiVersion: v1
kind: Service
metadata:
  name: web-app-service
spec:
  selector:
    app: web-app
  ports:
  - protocol: TCP
    port: 80
    targetPort: 8080
  type: LoadBalancer
```
Apply the deployment and service:
```bash
kubectl apply -f deployment.yaml
kubectl apply -f service.yaml
# verify
kubectl get deployments
kubectl get svc
kubectl get pods -o wide
```

![](/Kubernetes/HighlyAvailableCluster/deployment.png)

## Troubleshooting

- Get detailed information about a specific pod:
```bash
kubectl describe pod <pod-name> -n <namespace>
```
- Get pod logs:
```bash
kubectl logs <pod-name> -n <namespace>
```
- Get events:
```bash
kubectl get events -n <namespace>
```
- Get all resources
```bash
kubectl get all -n <namespace>
```
- If you want to delete a Pod forcibly, do the following:
```bash
kubectl delete pod <name> --namespace=<namespace> --grace-period=0 --force
```

1. [ERROR ExternalEtcdVersion]

- Check network connectivity

- Ensure that kubelet is running on etcd nodes:
```bash
sudo systemctl status kubelet
```

- Check kubelet logs:
```bash
sudo journalctl -u kubelet -xe
```

- Check static pod manifests in **/etc/kubernetes/manifests/etcd.yaml**

- Copy file **kubelet.conf** from **/etc/systemd/system/kubelet.service.d/** to **/etc/kubernetes/** and change **/etc/systemd/system/kubelet.service.d/20-etcd-service-manager.conf** :
```bash
[Service]
ExecStart=
ExecStart=/usr/bin/kubelet --config=/etc/kubernetes/kubelet.conf
Restart=always
```

- Verify configuration and restart `kubelet`
```bash
sudo systemctl daemon-reload
sudo systemctl restart kubelet
```

2. tlsv1 alert internal error

A TLS/SSL connection verifies the certificate against the host name specified for the connection.

[Github issue](https://github.com/fluent/fluentd/issues/3584#issuecomment-1010700979)

3. detected that the sandbox image "registry.k8s.io/pause:3.8" of the container runtime is inconsistent with that used by kubeadm. It is recommended that using "registry.k8s.io/pause:3.9" as the CRI sandbox image.
- replace the image version/tag in the config.toml 
  ```bash
  /etc/containerd/config.toml 
  sandbox_image = "registry.k8s.io/pause:3.8" > sandbox_image = "registry.k8s.io/pause:3.9"
  sudo systemctl restart containerd
  ```
4. coredns status pending:

Delete coredns and network plugin pods, re-apply flannel and restart containerd.

 ```bash
 journalctl -u kubelet -f
 kubectl delete pod <name> --namespace=<namespace> --grace-period=0 --force
 kubectl delete pod -n kube-flannel --all
 kubectl apply -f https://github.com/flannel-io/flannel/releases/latest/download/kube-flannel.yml
 # there should be interface cni0 with ip address 10.244.0.1
 ifconfig cni0
 # restart kubelet and containerd
 sudo systemctl restart kubelet
 sudo systemctl restart containerd
 ```

5. When joining a control-plane, the process hang with message Running pre-flight checks:

- Synchronize time on both servers.

## Useful Links

- [Creating Highly Available Clusters with kubeadm](https://kubernetes.io/docs/setup/production-environment/tools/kubeadm/high-availability/)
- [Set up a High Availability etcd Cluster with kubeadm](https://kubernetes.io/docs/setup/production-environment/tools/kubeadm/setup-ha-etcd-with-kubeadm/)
- [High Availability Considerations](https://github.com/kubernetes/kubeadm/blob/main/docs/ha-considerations.md#options-for-software-load-balancing)
- [Keepalived](https://www.server-world.info/en/note?os=Ubuntu_24.04&p=keepalived&f=1)
- [Ports and Protocols](https://kubernetes.io/docs/reference/networking/ports-and-protocols/)
- [Troubleshooting kubeadm](https://kubernetes.io/docs/setup/production-environment/tools/kubeadm/troubleshooting-kubeadm/)
