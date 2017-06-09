timestamps {
    node('docker') {
        /* clean out the workspace just to be safe */
        deleteDir()

        /* Grab our source for this build */
        checkout scm

        /* Make sure our directory is there, if Docker creates it, it gets owned by 'root' */
        sh 'mkdir -p $HOME/.m2'

        /* Share docker socket to run sibling container and maven local repo */
        String containerArgs = '-v /var/run/docker.sock:/var/run/docker.sock -v $HOME/.m2:/var/maven/.m2'

        String MVN = 'mvn -B -U -Dmaven.test.failure.ignore=true -Duser.home=/var/maven'

        stage("Build ATH container") {
            def uid = sh(script: 'id -u', returnStdout: true).trim()
            def gid = sh(script: 'id -g', returnStdout: true).trim()
            String buildArgs = "--build-arg=uid=${uid} --build-arg=gid=${gid} configurator/src/test/resources/ath-container"
            athContainer = docker.build('jenkins/ath', buildArgs)
        }

        stage('Build the plugin') {
            athContainer.inside(containerArgs) {
                sh "${MVN} clean install"
            }

            junit '**/target/surefire-reports/*.xml'
            archive '**/target/*.hpi'
            archive '**/target/*.jar'
        }

        stage('Run ATH tests') {
// TODO fixme
//            git url: 'git@github.com:jenkinsci/acceptance-test-harness.git'
//            dir("acceptance-test-harness") {
//                athContainer.inside(containerArgs) {
//                    def env = 'JENKINS_VERSION=1.609.3 foreman-node-sharing-plugin.jpi=../plugin/target/foreman-node-sharing.hpi'
//                    sh "env ${env} ${MVN} clean package -Dtest=ForemanNodeSharingPluginTest"
//                }
//            }
//
//            junit 'target/surefire-reports/*.xml'
//            archive 'target/diagnostics/**/*'
        }
    }
}
