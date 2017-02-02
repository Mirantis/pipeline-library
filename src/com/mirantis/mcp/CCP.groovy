package com.mirantis.mcp

def checkPortIsFree(String localPort) {
    withEnv(["port=${localPort}"]) {
        sh '''
            if netstat -pnat | grep -qw "${port}"; then
                echo "Port ${port} is already opened."
                exit 1
            fi
        '''
    }
}

def deployProxy(String confDir, String proxyPassURL, String localPort) {
    withEnv(["localPort=${localPort}",
             "proxyPassURL=${proxyPassURL}",
             "confDir=${confDir}"]) {
        sh '''\
            cat <<EOF > "${confDir}/nginx-${localPort}"
            worker_processes auto;
            user jenkins;
            pid ${confDir}/nginx-${localPort}.pid;
            error_log /dev/null;
            #
            events {
               worker_connections 768;
            }
            #
            http {
                sendfile on;
                tcp_nopush on;
                tcp_nodelay on;
                keepalive_timeout 65;
                types_hash_max_size 2048;
                #
                include /etc/nginx/mime.types;
                default_type application/octet-stream;
                #
                access_log off;
                #
                server {
                    listen ${localPort} default_server;
                    #
                    server_name _;
                    client_max_body_size 0;
                    # required to avoid HTTP 411: see Issue #1486 (https://github.com/docker/docker/issues/1486)
                    chunked_transfer_encoding on;
                    #
                    location / {
                        proxy_set_header  Host              \\$http_host;   # required for docker client's sake
                        proxy_set_header  X-Real-IP         \\$remote_addr; # pass on real client's IP
                        proxy_set_header  X-Forwarded-For   \\$proxy_add_x_forwarded_for;
                        proxy_set_header  X-Forwarded-Proto \\$scheme;
                        proxy_read_timeout                  900;
                        proxy_pass                          ${proxyPassURL} ;
                    }
                }
            }'''.stripIndent()
        }
    sh "nginx -t -c ${confDir}/nginx-${localPort}"
    sh "nginx -c ${confDir}/nginx-${localPort}"
    return "127.0.0.1:${localPort}"
}

def destroyProxy(String confDir, String localPort) {
    def proxyPid = sh(script: "cat ${confDir}/nginx-${localPort}.pid",
                      returnStdout: true).trim()
    if ( proxyPid ) {
        sh "kill -QUIT ${proxyPid}"
    }
}
