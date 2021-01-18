productVersion = "3.2.0"

node("master") {
    try {
	def root = tool name: 'Go 1.14', type: 'go'

   	stage('Preparation') {
	    checkout([$class: 'GitSCM', branches: [[name: "*/master"]], doGenerateSubmoduleConfigurations: false, extensions: [
		    [$class: 'LocalBranch', localBranch: "master"],
		    [$class: 'RelativeTargetDirectory', relativeTargetDir: 'product-apim-tooling']
	    ], submoduleCfg : [], userRemoteConfigs: [[url: 'https://github.com/uvindra/product-apim-tooling']]])
        }
        stage('Building WUM pack') {
            sh '''#!/bin/bash +x
                PRODUCT=am
                VERSION=''' + productVersion + '''
                PRODUCT_NAME=wso2$PRODUCT
                PRODUCT_NAME_WITH_VERSION=wso2$PRODUCT-$VERSION
                #PRODUCT_PATH=/home/ubuntu/.wum-wso2/products/$PRODUCT_NAME/$VERSION
		PRODUCT_PATH=$JENKINS_HOME/.wum3/products/$PRODUCT_NAME/$VERSION
                #WUM_PACK_PATH=/build/jenkins-home/jobs/apictl-$VERSION-integration/workspace/wum-distribution
		WUM_PACK_PATH=$JENKINS_HOME/workspace/apictl-$VERSION-integration/wum-distribution
                #WUM_HOME=/build/packs/wum/bin
		WUM_HOME=/bin
                
                mkdir -p $WUM_PACK_PATH
                cd $WUM_HOME
                ./wum config repositories.staging.enabled true                
                bash init.sh
                MESSAGE=$((./wum check-update $PRODUCT_NAME_WITH_VERSION -v) 2>&1)
                sleep 30
                echo $MESSAGE
                if [[ $MESSAGE == *"There are no new updates available for the product"* ]]; then
                    echo "Exit ..................."
                else
                    LATEST_PACK=$(find $PRODUCT_PATH/full/ \\( -name "$PRODUCT_NAME_WITH_VERSION+*.zip" \\))
                    echo $LATEST_PACK
                    ./wum update "$PRODUCT_NAME_WITH_VERSION" -v
                    if [ -f "$LATEST_PACK" ] ; then
                            echo "Removing older version: $LATEST_PACK"
                            rm "$LATEST_PACK"
                    fi
                    NEW_UPDATE=$(find $PRODUCT_PATH/full/ \\( -name "$PRODUCT_NAME_WITH_VERSION+*.zip" \\))
                    cd $PRODUCT_PATH/
                    NEW_UPDATE_WITHOUT_STAGING_PART="${NEW_UPDATE/.staging/}"
                    echo "MVing" $NEW_UPDATE $NEW_UPDATE_WITHOUT_STAGING_PART
                    mv $NEW_UPDATE $NEW_UPDATE_WITHOUT_STAGING_PART
                fi
                echo Copying wum updated pack to the hosting location
                NEW_UPDATE=$(find $PRODUCT_PATH/full/ \\( -name "$PRODUCT_NAME_WITH_VERSION+*.zip" \\))
                echo $NEW_UPDATE
                #Removing older version files
                rm -rf $WUM_PACK_PATH/$PRODUCT_NAME_WITH_VERSION*
                cd $PRODUCT_PATH
                cp "$NEW_UPDATE" $WUM_PACK_PATH/ -v
                cd $WUM_PACK_PATH/
                unzip -q $NEW_UPDATE -d .
    			sed -i 's/-Xms256m -Xmx1024m/-Xms1g -Xmx4g/g' $PRODUCT_NAME_WITH_VERSION/bin/wso2server.sh
    			rm -rf $WUM_PACK_PATH/$PRODUCT_NAME_WITH_VERSION.*.zip -v
			zip -qr $PRODUCT_NAME_WITH_VERSION.zip $PRODUCT_NAME_WITH_VERSION
    			rm -rf $PRODUCT_NAME_WITH_VERSION'''
        }	
	stage('Running integration tests') {
	    withEnv(["GOPATH=${WORKSPACE}", "PATH+GO=${root}/bin:${WORKSPACE}/bin", "GOBIN=${WORKSPACE}/bin"]) {
            sh '''#!/bin/bash +x
                PRODUCT=am
                VERSION=''' + productVersion + '''
                PRODUCT_NAME_WITH_VERSION=wso2$PRODUCT-$VERSION
                #WUM_PACK_PATH=$JENKINS_HOME/jobs/apictl-$VERSION-integration/workspace/wum-distribution
		WUM_PACK_PATH=$JENKINS_HOME/workspace/apictl-$VERSION-integration/wum-distribution
                
		#CTL_SRC_PATH=$JENKINS_HOME/jobs/apictl-$VERSION-integration/workspace/product-apim-tooling/import-export-cli
		CTL_SRC_PATH=$JENKINS_HOME/workspace/apictl-$VERSION-integration/product-apim-tooling/import-export-cli
		INTEGRATION_DIRECTORY=integration
		JENKINS_SCRIPT_DIRECTORY=jenkins-job-scripts
		BUILD_ARCHIVE=apictl-3.2.0-linux-x64.tar.gz

                PRODUCT_NAME=wso2$PRODUCT

		cd $WUM_PACK_PATH

		# Replace default deployment.toml
		unzip -q $PRODUCT_NAME_WITH_VERSION.zip
		cp $CTL_SRC_PATH/$INTEGRATION_DIRECTORY/$JENKINS_SCRIPT_DIRECTORY/deployment.toml $PRODUCT_NAME_WITH_VERSION/repository/conf/.
		zip -qr $PRODUCT_NAME_WITH_VERSION.zip $PRODUCT_NAME_WITH_VERSION
		rm -rf $PRODUCT_NAME_WITH_VERSION

		python -m SimpleHTTPServer 8001 &> /dev/null &
		FTPS_PID=$!
		echo Hosted WUM pack

		docker kill api-manager-1
		docker kill api-manager-2
		docker rm api-manager-1
		docker rm api-manager-2
		docker rmi wum/$PRODUCT_NAME_WITH_VERSION

		echo Starting APIM instances

		cd $CTL_SRC_PATH/$INTEGRATION_DIRECTORY/$JENKINS_SCRIPT_DIRECTORY

		docker build --build-arg WSO2_SERVER_DIST_URL=http://172.17.0.2:8001/$PRODUCT_NAME_WITH_VERSION.zip -t wum/$PRODUCT_NAME_WITH_VERSION .

		kill "${FTPS_PID}"
		
		docker run -dit --name api-manager-1 wum/$PRODUCT_NAME_WITH_VERSION

		APIM1_HOST=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' api-manager-1)
		
		export DEVELOPMENT_HOST=$APIM1_HOST
		export DEVELOPMENT_OFFSET="0"	

		docker run -dit --name api-manager-2 wum/$PRODUCT_NAME_WITH_VERSION
		
		APIM2_HOST=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' api-manager-2)

		export PRODUCTION_HOST=$APIM2_HOST
		export PRODUCTION_OFFSET="0"
		
		echo "DEVELOPMENT_HOST = ${DEVELOPMENT_HOST}"
		echo "DEVELOPMENT_OFFSET = ${DEVELOPMENT_OFFSET}"
		echo "PRODUCTION_HOST = ${PRODUCTION_HOST}"
		echo "PRODUCTION_OFFSET = ${PRODUCTION_OFFSET}"

		APIM1_STATUS=404
		while [[ $APIM1_STATUS != 200 ]]
		do
		   APIM1_STATUS=$(curl --write-out %{http_code} --silent --output /dev/null "http://$APIM1_HOST:9763/services/Version")
		done

		echo "APIM 1 Instance started"


		APIM2_STATUS=404
		while [[ $APIM2_STATUS  != 200 ]]
		do
		    APIM2_STATUS=$(curl --write-out %{http_code} --silent --output /dev/null "http://$APIM2_HOST:9763/services/Version")
		done

		echo "APIM 2 Instance started"
		
		cd $CTL_SRC_PATH	
		
		./build.sh -t apictl.go -v $VERSION

		cd $INTEGRATION_DIRECTORY

		go test -p 1 -timeout 0 -archive $BUILD_ARCHIVE'''
		}
	}
	currentBuild.result = 'SUCCESS'
    } catch (any) {
        if (!currentBuild.result == 'UNSTABLE') {
            currentBuild.result = 'FAILURE'
        }
        throw any //rethrow exception to prevent the build from proceeding
    } finally {
        step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients: 'uvindra@wso2.com', sendToIndividuals: true])
    }
}
