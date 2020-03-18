# 12 factor apps in practice

This is a practical look at [12 factor](https://12factor.net) apps and how to build them for real. Its based on several years experience across a number of large financial organisations in teams with a strong focus on engineering and doing things well.     

This is part one of a two part series covering about half the factors. 

Different approaches will be explored through the use of a Java ([Spring Boot](https://spring.io/projects/spring-boot)) based app, [docker](https://www.docker.com), [kubernetes](https://kubernetes.io) (and a bit of [OpenShift](https://www.openshift.com)). So heavy focus on containers, but cloud based services provide options as well, and these will be noted.

Source is available in [twelve_factor](https://github.com/stehrn/twelve_factor) github repo

## Introducing the demo app
Our app is the beginnings of a simple agle scrum _moodboard_, microservice based architecture that has the following end points to get and set a users mood:
```
GET /user/<user>/mood/
PUT /user/<user>/mood/
```
Implemented as a Spring Boot Java app  - as usual, super quick and easy to get started, helped by [Spring Initializr](https://start.spring.io) which was used to generate template project and pom. 

Run [HttpRequestTest.java](src/test/java/com/github/stehrn/mood/HttpRequestTest.java) to test the 2 end points, or test via curl, bring up app first:
```cmd
$ mvn package
$ mvn spring-boot:run
```
...then set mood and retrieve:
```cmd
$ curl -X PUT  -H "Content-Type: text/plain" -d "happy" http://localhost:8080/user/stehrn/mood 
$ curl http://localhost:8080/user/stehrn/mood
{"user":"stehrn","mood":"happy"}
```


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
The initial version the the demo app has a bit of config defined in [application.properties](src/main/resources/application.properties) to set the 404 message when no mood set for user. Its read in [MoodService.java](MoodService.java) via:
```java
@Value("${mood_not_found_message}")
private String moodNotFoundMessage;
```
So right now it appears its hard coded, to change the value requires us to rebuild the app. But we're in luck, Spring Boot will also detect environment variables, treating them as properties, we just need to `export` the new value:
```cmd
$ export mood_not_found_message="no mood has been set for this user"
$ mvn spring-boot:run
$ curl http://localhost:8080/user/stehrn/mood
{... "status":404, "message":"no mood has been set for this user"}
```

Spring provides _config as a service_ via [Spring Cloud Config](https://cloud.spring.io/spring-cloud-config/reference/html/), which enables the discovery of application properties via a service running on a different part of the network. It's ok...if you're using Spring.    

kubernetes [configMaps](https://kubernetes.io/blog/2016/04/configuration-management-with-containers/) are great for storing, well, config, and we'll see this is action later.

It worth mentioning in passing [HashiCorp](https://www.hashicorp.com) products, popular on both cloud and container based platforms, [consul](https://www.consul.io) can be used for dynamic configuration, and [vault](https://www.hashicorp.com/products/vault) for storing secrets and passwords; most cloud providers also have their own equivalents with offerings like [Azure Key Vault](https://azure.microsoft.com/en-gb/services/key-vault/).   

## Introducing a container based app
Now is a good time to put our app into a Docker container and demonstrate some of the things covered. This is detail light, [docker.com](https://www.docker.com) is a great resource to learn more though.

Pre-requisite is we've created a far jar for the app:
```cmd
$ mvn package
```
Next [build](https://docs.docker.com/engine/reference/commandline/build/) the container image from the [Dockerfile](docker/Dockerfile) and tag it with `latest`. Note the Dockerfile uses the [ENV](https://docs.docker.com/engine/reference/builder/#env) command to set `mood_not_found_message` 
```cmd
$ cd docker
$ docker build --file Dockerfile --tag mood-app:latest .
$ docker image ls mood-app
REPOSITORY          TAG                 IMAGE ID            CREATED             SIZE
mood-app            latest              2fe60c820c21        3 minutes ago       103MB
```
(note the size, its pretty big for such a simple app, we'll come back to that in a later post when looking at _disposability_)

Now to [run](https://docs.docker.com/engine/reference/commandline/run/) container, call it `mood` and publish its `8080` port so we can connect:
```cmd
docker run --name mood --detach --publish 8080:8080 mood-app:latest
docker logs mood --follow
```
Check new default set via ENV command is observed:  
```cmd
$ curl http://localhost:8080/user/stehrn/mood
{"user":"stehrn","mood":"default for docker"}
```
Lets override this with a value injected in at runtime, when the container starts: 
```cmd
$ docker stop mood
$ docker rm mood
$ docker run --name mood --detach --publish 8080:8080 --env mood_not_found_message="runtime default for docker" mood-app:latest
$ curl http://localhost:8080/user/stehrn/mood
{... "status":404, "message":"runtime default for docker"}

```
That's as simple as containers get. So when do we we use one approach over the other for setting env variables? Creating the container image is something that generally happens at _build_ time. When we're ready to ship the latest version, a _release_ is created for one or more target envs. Given the application will need to be configured differently for each env, we dont want to encode anything into the binary, unless its a common default applicable to _all_ envs, otherwise best policy is to inject env variables in at runtime. 

Of course, passing in variables via the command line wont cut it in all but trivial apps, sourcing from files is another option (Docker support this), but lets hold off, since most real world apps wont be running docker directly, but rather a higher level container orchestration platform like kubernetes, which we'll come to in a bit and re-visit config.   
 

## (Factor 4) Backing Services - treat Backing Services as attached resources 

## (Factor 5) Build, release, run - strictly separate stages
 
## (Factor 6) Process - Execute the app as one or more stateless processes
So how to get state out of the app? The answer is to introduce a _cache_ backing service and store state there. 

[redis](https://redis.io) is a good choice, defined on the website as "an in-memory data structure store, used as a database, cache and message broker ... supports data structures such as strings, hashes, lists, sets, ...". Its a great bit of opensource with a strong community, so lets use it; alternatives may include Hazelcast or memcached 

We'll replace our existing in memory cache with a distributed redis cache using [Spring Data Redis](https://docs.spring.io/spring-data/data-redis/docs/current/reference/html/#reference)

Set up port forwarding so that we can connect to the container using port 7001, the docker run command is:

`docker run --name twelve-factor-redis-container -p 7001:6379 -d redis`







## References
* https://www.ionos.com/community/hosting/redis/using-redis-in-docker-containers/
* https://docs.spring.io/spring-data/data-redis/docs/current/reference/html/#reference
* https://www.contino.io/insights/building-a-twelve-factor-application-part-one