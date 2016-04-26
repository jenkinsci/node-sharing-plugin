# foreman-slave
Jenkins plugin to allow a Foreman instance to provide Jenkins slaves.

# Building
mvn clean package -Dmaven.test.skip=true
mkdir plugins
cp -fv target/foreman-slave.hpi  plugins/foreman-slave.jpi
mvn clean install

