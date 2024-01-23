FROM openjdk:17-alpine

RUN mkdir -p /softwares/dnsdock-java

ADD target/dnssqdock-java.jar /softwares/dnsdock-java

CMD java -jar /softwares/dnsdock-java/dnssqdock-java.jar

EXPOSE 53