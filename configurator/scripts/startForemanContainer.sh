docker build -t jenkins/foreman:scratch ../../foreman-container
docker run -d -p 3000:3000 jenkins/foreman:scratch
