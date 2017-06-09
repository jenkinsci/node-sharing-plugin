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

        stage("Build ATH container") {
            def uid = sh(script: 'id -u', returnStdout: true).trim()
            def gid = sh(script: 'id -g', returnStdout: true).trim()
            String buildArgs = "--build-arg=uid=${uid} --build-arg=gid=${gid} src/test/resources/ath-container"
            athContainer = docker.build('jenkins/ath', buildArgs)
        }

        stage('Build the plugin') {
            athContainer.inside(containerArgs) {
                sh 'mvn -B -U -e -Dmaven.test.failure.ignore=true clean validate'
            }

            junit '**/target/surefire-reports/*.xml'
            archive '**/target/**/*.hpi'
            archive '**/target/**/*.jar'
        }

        stage('Run ATH tests') {
            // TODO
        }
// TODO create junit tests
//                 // let foreman-host-configurator build jar
//                sh 'rm -f target/foreman-host-configurator.jar'
//                def r = sh script: './foreman-host-configurator --help', returnStatus: true
//                if (r != 2) {
//                    error('failed to run foreman-host-configurator --help')
//                }
    }
}
