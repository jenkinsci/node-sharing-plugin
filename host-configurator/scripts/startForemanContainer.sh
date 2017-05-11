docker build -t jenkins/foreman:scratch src/main/resources/com/scoheb/foreman/cli/docker/fixtures/ForemanContainer 
docker run -d -p 3000:3000 jenkins/foreman:scratch
