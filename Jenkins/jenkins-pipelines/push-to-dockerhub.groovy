pipeline {
    agent any

    environment {
        GITHUB_REPO_URL = 'git@github.com:pawel-rus/Vulnerable-App-CTF.git'   
        DOCKERHUB_CREDENTIALS_ID = 'dockerhub-credentials' 
        IMAGE_NAME = 'vulnerable-app-ctf'
        IMAGE_TAG = "${env.BUILD_ID}"
    }
    
    stages {
        stage('Set DockerHub Username') {
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: DOCKERHUB_CREDENTIALS_ID, usernameVariable: 'DOCKER_USER', passwordVariable: '_')]) {
                        env.DOCKERHUB_USERNAME = "${DOCKER_USER}"
                    }
                }
            }
        }
    

        stage('Clone Repository') {
            steps {
                git branch: 'main', credentialsId: 'github-ssh-key', url: GITHUB_REPO_URL
            }
        }

        stage('Build Docker Image') {
            steps {
                script {
                    docker.build("${DOCKERHUB_USERNAME}/${IMAGE_NAME}:${IMAGE_TAG}")
                }
            }
        }

        stage('Push Image to DockerHub') {
            steps {
                script {
                    docker.withRegistry('https://index.docker.io/v1/', DOCKERHUB_CREDENTIALS_ID) {
                        docker.image("${DOCKERHUB_USERNAME}/${IMAGE_NAME}:${IMAGE_TAG}").push()
                    }
                }
            }
        }
    }
    
    post {
        always {
            script {
                sh "docker logout"
            }
        }
        success {
            echo 'Pipeline finished successfully!'
        }
        failure {
            echo 'Pipeline failed.'
        }
    }
}
