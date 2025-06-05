pipeline {
    agent any
    options {
        skipDefaultCheckout(true)
    }
    stages {
        stage('Code checkout from GitHub') {
            steps {
                script {
                    cleanWs()
                    git credentialsId: 'github-token', url: 'https://github.com/pawel-rus/abcd-student', branch: 'main'
                }
            }
        }
        
        stage('[ZAP] passive-scan') {
            steps {
                sh 'mkdir -p ${WORKSPACE}/results/'
                sh 'mkdir -p results/'

                sh '''
                    docker run --name juice-shop -d --rm \
                        -p 3000:3000 \
                        bkimminich/juice-shop
                    sleep 5
                '''
         
                sh '''
                    docker run --name zap \
                        --add-host=host.docker.internal:host-gateway \
                        -v /home/pawel/DevSecOps/abcd-lab/zap:/zap/wrk/:rw \
                        -t ghcr.io/zaproxy/zaproxy:stable \
                        bash -c "zap.sh -cmd -addonupdate; zap.sh -cmd -addoninstall communityScripts -addoninstall pscanrulesAlpha -addoninstall pscanrulesBeta -autorun /zap/wrk/passive.yaml" || true
                '''
            }
        }
        stage('[OSV] Scan package-lock.json') {
                steps {
                    script {
                        sh 'osv-scanner scan --lockfile package-lock.json --format json > "${WORKSPACE}/results/osv_scan.json"  || true'
                sh 'osv-scanner scan --lockfile package-lock.json --format table > "${WORKSPACE}/results/osv_scan.txt"  || true'
                    }
                }
            }
            
        stage('[TruffleHog] Scan main branch') {
                steps {
                    script {
                        sh 'trufflehog git file://. --branch main --only-verified --fail --json > "${WORKSPACE}/results/trufflehog_scan.json" || true'
                    }
                }
            }
        stage('[Semgrep] Scan') {
                steps {
                    script {
                        sh 'semgrep scan --config auto --json-output="${WORKSPACE}/results/semgrep_scan.json"'
                    }
                }
            }
        }
        post {
            always {
                sh '''
                    docker cp zap:/zap/wrk/reports/zap_html_report.html ${WORKSPACE}/results/zap_html_report.html
                    docker cp zap:/zap/wrk/reports/zap_xml_report.xml ${WORKSPACE}/results/zap_xml_report.xml
                    docker stop zap juice-shop
                    docker rm zap
                '''
                archiveArtifacts artifacts: 'results/zap_*.html, results/zap_*.xml, results/osv_scan.json, results/osv_scan.txt, results/trufflehog_scan.json, results/semgrep_scan.json', fingerprint: true

            }
    }
}