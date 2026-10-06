// Declarative pipeline: build -> test + coverage -> image -> ECR -> EKS
// Jenkins needs: Docker, kubectl + aws CLI on the agent, credentials 'aws-ecr-eks' (AWS access key) configured.
pipeline {
  agent any

  environment {
    AWS_REGION   = 'ap-south-1'
    ECR_REGISTRY = "${env.AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
    IMAGE_REPO   = 'securebank-core'
    IMAGE_TAG    = "${env.GIT_COMMIT?.take(8) ?: env.BUILD_NUMBER}"
    EKS_CLUSTER  = 'securebank-prod'
    K8S_NAMESPACE = 'securebank'
  }

  options {
    timestamps()
    disableConcurrentBuilds()
    timeout(time: 30, unit: 'MINUTES')
  }

  stages {
    stage('Build & Unit Test') {
      agent { docker { image 'maven:3.9-eclipse-temurin-17'; args '-v $HOME/.m2:/root/.m2' } }
      steps {
        // verify = compile + unit tests + JaCoCo report + JaCoCo coverage gate (see jacoco.minimum in pom.xml)
        sh 'mvn -B clean verify'
      }
      post {
        always {
          junit 'target/surefire-reports/*.xml'
          archiveArtifacts artifacts: 'target/site/jacoco/**', allowEmptyArchive: true
          // With the Jenkins "Coverage" plugin you can also publish:
          // recordCoverage tools: [[parser: 'JACOCO', pattern: 'target/site/jacoco/jacoco.xml']]
        }
      }
    }

    stage('Integration Tests (Testcontainers)') {
      when { anyOf { branch 'main'; branch 'release/*' } }
      agent { docker { image 'maven:3.9-eclipse-temurin-17'; args '-v /var/run/docker.sock:/var/run/docker.sock -v $HOME/.m2:/root/.m2' } }
      steps { sh 'mvn -B verify -Pintegration' }
    }

    stage('Docker Build') {
      steps { sh 'docker build -t ${IMAGE_REPO}:${IMAGE_TAG} .' }
    }

    stage('Push to ECR') {
      when { branch 'main' }
      steps {
        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: 'aws-ecr-eks']]) {
          sh '''
            aws ecr get-login-password --region $AWS_REGION | docker login --username AWS --password-stdin $ECR_REGISTRY
            docker tag ${IMAGE_REPO}:${IMAGE_TAG} $ECR_REGISTRY/${IMAGE_REPO}:${IMAGE_TAG}
            docker push $ECR_REGISTRY/${IMAGE_REPO}:${IMAGE_TAG}
          '''
        }
      }
    }

    stage('Deploy to EKS') {
      when { branch 'main' }
      steps {
        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: 'aws-ecr-eks']]) {
          sh '''
            aws eks update-kubeconfig --name $EKS_CLUSTER --region $AWS_REGION
            kubectl -n $K8S_NAMESPACE set image deployment/securebank-core app=$ECR_REGISTRY/${IMAGE_REPO}:${IMAGE_TAG}
            kubectl -n $K8S_NAMESPACE rollout status deployment/securebank-core --timeout=300s
          '''
        }
      }
    }
  }

  post {
    failure { echo 'Pipeline failed - check test and coverage reports.' }
  }
}
