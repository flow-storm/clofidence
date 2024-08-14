.PHONY: lint

clean:
	clj -T:build clean

lint:
	clj-kondo --config .clj-kondo/config.edn --lint src

clofidence.jar:
	clj -T:build jar

install: clofidence.jar
	mvn install:install-file -Dfile=target/clofidence.jar -DpomFile=target/classes/META-INF/maven/com.github.flow-storm/clofidence/pom.xml

deploy:
	mvn deploy:deploy-file -Dfile=target/clofidence.jar -DrepositoryId=clojars -DpomFile=target/classes/META-INF/maven/com.github.flow-storm/clofidence/pom.xml -Durl=https://clojars.org/repo

test:
	clj -X:dev:test
