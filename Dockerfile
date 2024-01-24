FROM openjdk:17-alpine

RUN mkdir -p /softwares/dnsdock-java

ADD target/dnssqdock-java.jar /softwares/dnsdock-java

CMD java -Xmx128m -Xms64m -jar /softwares/dnsdock-java/dnssqdock-java.jar

EXPOSE 53 80