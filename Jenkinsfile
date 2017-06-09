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

        stage('Build/Test Foreman Container') {
            dir('foreman-container') {
                def buildArgs = "."
                docker.build('jenkins/foreman', buildArgs)
                String foremanContainerArgs = '-p 3000:3000'
                docker.image('jenkins/foreman').withRun(foremanContainerArgs) {
                    timeout(5) {
                        waitUntil {
                            def r = sh script: 'wget -q http://localhost:3000 -O /dev/null', returnStatus: true
                            return r == 0
                        }
                    }
                }
            }
        }

        stage('Build/Test Host Configurator') {

            docker.image('maven:3.3-jdk-8').inside(containerArgs) {

                sh 'cd foreman-host-configurator ; mvn -B -U -e -Duser.home=/var/maven -Dmaven.test.failure.ignore=true clean install -DskipTests'

                // let foreman-host-configurator build jar
                sh 'cd foreman-host-configurator ; rm -f target/foreman-host-configurator.jar'
                def r = sh script: 'cd foreman-host-configurator ; ./foreman-host-configurator --help', returnStatus: true
                if (r != 2) {
                    error('failed to run foreman-host-configurator --help')
                }
                // now let it use artifact
                sh 'cd foreman-host-configurator ; mv target/foreman-host-configurator.jar foreman-host-configurator.jar'
                r = sh script: 'cd foreman-host-configurator ; ./foreman-host-configurator --help', returnStatus: true
                if (r != 2) {
                    error('failed to run foreman-host-configurator --help')
                }
            }
            def uid = sh(script: 'id -u', returnStdout: true).trim()
            def gid = sh(script: 'id -g', returnStdout: true).trim()
            String buildArgs = "--build-arg=uid=${uid} --build-arg=gid=${gid} foreman-node-sharing-plugin/src/test/resources/ath-container"
            docker.build('jenkins/ath', buildArgs)
            docker.image('jenkins/ath').inside(containerArgs) {
                sh '''
                cd foreman-host-configurator ; mvn clean test -Dmaven.test.failure.ignore=true -Duser.home=/var/maven -B
                '''
            }

            dir('foreman-host-configurator') {
                junit 'target/surefire-reports/*.xml'
                archive 'foreman-host-configurator'
                archive 'foreman-host-configurator.jar'
            }
        }

        stage('Test Plugin') {
            dir('foreman-node-sharing-plugin') {
                docker.image('jenkins/ath').inside(containerArgs) {
                    sh '''
                    eval $(./vnc.sh 2> /dev/null)
                    mvn test -Dmaven.test.failure.ignore=true -Duser.home=/var/maven -DforkCount=1 -B
                    '''
                }

                junit 'target/surefire-reports/*.xml'
                archive 'target/**/foreman-*.hpi'
                archive 'target/diagnostics/**'
            }
        }
    }
}
