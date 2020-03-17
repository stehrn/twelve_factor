# 12 factor apps in practice

This is a practical look at [12 factor](https://12factor.net) apps and how to build them for real. Its based on several years experience across a number of large financial organisations in teams with a strong focus on engineering and doing things well.     

This is part one of a two part series looking at the first 6 factors. 

We'll use a Java ([Spring Boot](https://spring.io/projects/spring-boot)) based app, [docker](https://www.docker.com), and [OpenShift](https://www.openshift.com) to demonstrate the different approaches. So heavy focus on containers, but cloud based services provide options as well, and these will be noted.

Source is available in (twelve_factor)[TODO] github repo

## Introducing the demo app
Our app is the beginnings of a simple agle scrum _moodboard_, microservice based architecture that has the following end points:
```
GET /user/<user>/mood/
POST /user/<user>/mood/
```
Implemented as a Spring Boot Java app  - as usual, super quick and easy to get started, helped by [Spring Initializr](https://start.spring.io) which was used to generate based project and pom. 

## (Factor 1) Codebase - one codebase, many deploys
This is much easier to do if you store config in the environment (factor 3 below). Also spend the time to refactor out libraries when you find yourself sharing code across applications, pull out code into a new repo with its own build and release process, and reference release binaries via your dependency management. Applications can then evolve at their own pace by referencing relevant library version (yes, versions may be different) - this is all about reducing risk of breaking something, and will generally save you time in the long run. Just be aware the code you are refactoring out should have a different velocity of change unrelated to your applications  if you find yourself releasing the app and library each time, you're not on track.

One last thing to note, use [git-flow](https://nvie.com/posts/a-successful-git-branching-model/) and feature branches to support different versions of your applications in the same repo. Our sample project makes use of feature branches to demonstrate some of enhancements, which were merged back to master (normally feature branch is deleted to keep things trim).          

## (Factor 2) Dependencies - explicitly declare and isolate dependencies
As engineers, most of us will know how to manage dependencies using tools like maven, gradle (people seem to love gradle now), or Ivy for Java projects, Godep and Godm for Golang, and many more language specific tools. These are used to manage dependencies required by our application at build and runtime. 

Check out the maven `dependencies` in the [pom.xm](pom.xml) for the demo app, the versions, if you were wondering, are inherited from the parent pom. 

Dependencies with _provided_ scope are worth a special mention - these wont be packaged into your distribution, the assumption been they will be provided at runtime, so how then to define this in the runtime? This is where containers help, we'll come back to an example below where we define a runtime dependency inside a docker container. 

What about dependencies to other resources and making sure they are available when our application runs? Things that belong to our own application ecoystem, their lifecycle matches that of the app (think UML composite aggregation, the black diamond, if a composite is deleted, all other parts associated with it are deleted). A clue is based on _responsibility_, these are generally resources the app team are responsible for as well. This could include things like a database, distributed cache, or microservices; it would not include any shared services (e.g. logging or security) or third party dependencies. 

Possible ways to explicitly declare such dependencies include:
* Container dependencies - as noted above, TODO
* Infrastructure as code (IaC) - define infrastructure in a machine readable config that's version controlled. IaC model generates the same environment every time it is applied. Azure provides [quite a nice description of IaC](https://docs.microsoft.com/en-us/azure/devops/learn/what-is-infrastructure-as-code).
* Chef/Puppet - TODO    



## (Factor 3) Config - store config in the environment (not in the code)


For cloud based apps [HashiCorp](https://www.hashicorp.com) is winning everyone over, their [consul](https://www.consul.io) product can be used for dynamic configuration, and [Vault](https://www.hashicorp.com/products/vault) for storing secrets and passwords; that said, must providers like Azure have their own equivalents with offerings like [Azure Key Vault](https://azure.microsoft.com/en-gb/services/key-vault/)   

Spring provides config as a service via [Spring Cloud Config](https://cloud.spring.io/spring-cloud-config/reference/html/) 

## (Factor 4) Backing Services - treat Backing Services as attached resources 

## (Factor 5) Build, release, run - strictly separate stages
 
## (Factor 6) Process - Execute the app as one or more stateless processes
So how to get state out of the app? The answer is to introduce a _cache_ backing service and store state there. 

[redis](https://redis.io) is a good choice, defined on the website as "an in-memory data structure store, used as a database, cache and message broker ... supports data structures such as strings, hashes, lists, sets, ...". Its a great bit of opensource with a strong community, so lets use it; alternatives may include Hazelcast or memcached 

We'll replace our existing in memory cache with a distributed redis cache using [Spring Data Redis](https://docs.spring.io/spring-data/data-redis/docs/current/reference/html/#reference)

Set up port forwarding so that we can connect to the container using port 7001, the docker run command is:

`docker run --name twelve-factor-redis-container -p 7001:6379 -d redis`







## References
https://www.ionos.com/community/hosting/redis/using-redis-in-docker-containers/
https://docs.spring.io/spring-data/data-redis/docs/current/reference/html/#reference
https://www.contino.io/insights/building-a-twelve-factor-application-part-one