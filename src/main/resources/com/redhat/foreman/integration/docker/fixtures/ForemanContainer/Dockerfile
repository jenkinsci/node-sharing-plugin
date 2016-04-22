FROM fedora:21
MAINTAINER Alexander Braverman "abraverm@redhat.com"

# Setting variables
ENV RAILS_ENV production

ENV FOREMAN="1.7-stable"
ENV RUBY_VER="2.0.0"
ENV WITHOUT="mysql2 pg test"

# Installing dependencies
# Puppet, libraries and tools
RUN rpm -ivh http://yum.puppetlabs.com/fedora/f21/products/x86_64/puppetlabs-release-21-11.noarch.rpm
RUN yum install -y -q tar sudo git puppet gcc-c++ patch \
    libvirt-devel openssl-devel libxml2-devel sqlite-devel \
    libxslt-devel zlib-devel readline-devel \
    postgresql-devel mysql-devel  readline readline-devel zlib \
    libyaml-devel libffi-devel make bzip2 autoconf automake libtool bison

# RVM - for running with 1.9.3
##ARG RUBY_VER
RUN curl -sSL https://rvm.io/mpapis.asc | gpg2 --import -
RUN curl -L get.rvm.io | bash -s stable
ENV PATH $PATH:/usr/local/rvm/bin/
RUN rvm requirements && rvm install $RUBY_VER
RUN rvm $RUBY_VER rubygems current
RUN rvm $RUBY_VER do gem install rails mysql2

# Downloading source
##ARG FOREMAN
WORKDIR /opt
RUN echo "Cloning Foreman $FOREMAN"; git clone https://github.com/theforeman/foreman.git -b $FOREMAN --depth 1
WORKDIR /opt/foreman

# Add plugins
ADD plugins/* bundler.d/

# Initialiazing foreman
##ARG WITHOUT
RUN cp config/settings.yaml.example config/settings.yaml && cp config/database.yml.example config/database.yml
RUN rvm $RUBY_VER do gem install bundler
RUN rvm $RUBY_VER do bundle install --without $WITHOUT --path vendor
RUN rvm $RUBY_VER do bundle exec rake db:migrate
RUN rvm $RUBY_VER do bundle exec rake db:seed assets:precompile locale:pack
RUN rvm $RUBY_VER do bundle exec rake permissions:reset password=changeme
RUN rvm $RUBY_VER do bundle exec rake apipie:cache

# Finalize
EXPOSE 3000
ENV RUBY_VER $RUBY_VER
CMD /usr/local/rvm/bin/rvm $RUBY_VER do /opt/foreman/script/rails s -e production
