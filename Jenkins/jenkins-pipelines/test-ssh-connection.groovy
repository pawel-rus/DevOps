pipeline {
    agent any

    environment {
        DEPLOY_SERVER = 'dev_user@10.0.0.7'
        SSH_CREDENTIALS_ID = 'jenkins-ssh-key'
    }

    stages {
        stage('Test SSH Connection') {
            steps {
                script {
                    sshagent([SSH_CREDENTIALS_ID]) {
                        sh """
                        ssh -o StrictHostKeyChecking=no ${DEPLOY_SERVER} 'echo SSH connection successful!'
                        """
                    }
                }
            }
        }
    }
}
