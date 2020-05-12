# 12 factor apps in practice

This is a practical look at [12 factor](https://12factor.net) apps and how to build them for real. Its based on several years experience across a number of large financial organisations in teams with a strong focus on engineering and doing things well.     

A simple Java microservice app is evolved architecturally, from a single process app, to a [docker](https://www.docker.com) containerised distributed app, first running on a container orchestration platform ([kubernetes](https://kubernetes.io) and [OpenShift](https://www.openshift.com)), then on full-blown public cloud ([Azure](https://azure.microsoft.com/en-gb/)).

This is part one of a two part series covering about half the factors and all source is available in [twelve_factor](https://github.com/stehrn/twelve_factor) GitHub repo
 
## Introducing the demo app
Our demo app is the beginnings of a simple agle scrum _moodboard_, implemented as a microservice based architecture that has the following end points to get and set a users mood:
```
GET /user/<user>/mood/
PUT /user/<user>/mood/
```

The ([Spring Boot](https://spring.io/projects/spring-boot)) Java app was quick and easy to implement, helped by [Spring Initializr](https://start.spring.io) which generated a base maven project. 

Run [HttpRequestTest](src/test/java/com/github/stehrn/mood/HttpRequestTest.java) to test the 2 end points, or test via `curl`, bring up app first:
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

## Codebase 
[one codebase, many deploys](https://12factor.net/codebase) (12 factor)

The app lives in a single repo, and that repo only contains code for the moodboard service, nothing else.
  
#### What to do with shared code  
Spend the time to refactor out _libraries_ when you find yourself sharing code across applications, pull out code into a new repo with its own build and release process, and reference release binaries via your dependency management. Applications can then evolve at their own pace by referencing relevant library version (yes, versions may be different) - this is all about reducing risk of breaking something, and will generally save you time in the long run. Just be aware the code you are refactoring out should have a different velocity of change unrelated to your applications  if you find yourself releasing the app and library each time, you're not on track.

#### Versions 
Use [git-flow](https://nvie.com/posts/a-successful-git-branching-model/) and feature branches to support different versions of your applications in the same repo. The demo project makes use of feature branches to demonstrate some of the enhancements (normally feature branches would be merged back to master and deleted to keep things trim).          

TODO: add link to branch

## Dependencies 
[explicitly declare and isolate dependencies](https://12factor.net/dependencies) (12 factor)

#### Libraries
As engineers, most of us will know how to manage library dependencies (other binaries our code references either directly or indirectly) using tools like maven, gradle (beating maven in popularity), or Ivy for Java projects, Godep and Godm for Golang, and many more language specific tools. These are used to manage dependencies required by our application at build and runtime. 

The demo app uses maven for dependencies management, defined in `dependencies` section of the [pom.xm](pom.xml), for example:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```
(_the versions for this dependency, if you were wondering, are inherited from the parent pom_)

Dependencies with _provided_ scope are worth a special mention - these wont be packaged into your distribution, the assumption been they will be provided at runtime, so how then to define this in the runtime? This is where containers help, we'll come back to an example below where we define a runtime dependency inside a docker container. 

## Config 
[store config in the environment (not in the code)](https://12factor.net/config) (12 factor)

The initial version the the demo app has a bit of config defined in [application.properties](src/main/resources/application.properties) to set the HTTP not found/404 message when no mood set for user: 
```
mood_not_found_message=no mood
```
Its read in [MoodService.java](MoodService.java) via:
```java
@Value("${mood_not_found_message}")
private String moodNotFoundMessage;
```
So right now it appears its hard coded, to change the value requires us to rebuild the app. But we're in luck, Spring Boot will also detect environment variables, treating them as properties, we just need to `export` the new value into the env before starting the process:
```cmd
$ export mood_not_found_message="no mood has been set for this user"
$ mvn spring-boot:run
$ curl http://localhost:8080/user/stehrn/mood
{... "status":404, "message":"no mood has been set for this user"}
```

Spring also provides _config as a service_ via [Spring Cloud Config](https://cloud.spring.io/spring-cloud-config/reference/html/) - this goes well beyond just storing config in the environment the process is running within - it enables the discovery of application properties via a service running on a different part of the network. It's an alternative option..._if_ you're using Spring, the techniques we'll go through below are framework agnostic and therefore preferable.     

## Process 
[Execute the app as one or more stateless processes](https://12factor.net/processes) (12 factor)

State exists in the demo app, in the form of a simple in-process cache in [MoodService.java](src/main/java/com/github/stehrn/mood/MoodService.java). So how to get state out of the app process? The answer is to introduce a _backing service_ and store state there. 

Why bother? If we want to _scale out_ our process and create multiple instances running on different servers across a network (yes, a distributed app), then having no state makes things much easier - you just run the process on another host. 

So how do we do this for our app? [redis](https://redis.io) is a good choice, defined on its website as "an in-memory data structure store, used as a database, cache and message broker ... supports data structures such as strings, hashes, lists, sets, ...". Its a great bit of opensource with a strong community, so lets use it; alternatives might include Hazelcast or memcached.

#### Running redis on docker
The quickest and easiest way to install and run redis is using docker, the [run](https://docs.docker.com/engine/reference/commandline/run/) command starts the redis container:
```
$ docker run --name mood-redis -p 6379:6379 -d redis
```
(port forwarding is set so that we can connect to the container using port 6379)

To check the running process and tail the logs:
```
$ docker ps
$ docker logs --follow mood-redis
```
The log should show the message `Ready to accept connections`, default logging does not actually tell us much, so lets get a bit more interactive using the redis command line interface [(redis-cli)](https://redis.io/topics/rediscli). 

To access redis-cli via docker, open an interactive (`it`) shell against the running redis container and run the `redis-cli` command:  
```
docker exec -it mood-redis sh -c redis-cli 
``` 
Use the [`monitor`](https://redis.io/topics/rediscli#monitoring-commands-executed-in-redis) command to actively monitor the commands running against redis - it will just print out `OK` to begin with, we'll see more once the Spring Boot app is connected to redis. 

#### Connecting Spring Boot to redis
Lets replace the existing in memory cache with a redis cache using [Spring Data Redis](https://docs.spring.io/spring-data/data-redis/docs/current/reference/html/#reference), not many changes are required:  
* [`RedisConfig`](src/main/java/com/github/stehrn/mood/RedisConfig.java) has been added to provide relevant redis configuration information for Spring to connect to redis, it includes a `RedisTemplate` Spring Data bean uses to interact with the Redis server and a `RedisConnectionFactory`
* The addition of `@RedisHash("user")` to [`Mood`](src/main/java/com/github/stehrn/mood/Mood.java) tells Spring to store the mood data in redis and not its default `KeyValue` store 

In a new terminal, start the Spring app (on port 8090):
```
export SERVER_PORT=8090
mvn spring-boot:run
```
...and set a new mood
```
$ curl -X PUT  -H "Content-Type: text/plain" -d "liking redis" -i http://localhost:${SERVER_PORT}/user/stehrn/mood 
HTTP/1.1 200 

$ curl http://localhost:${SERVER_PORT}/user/stehrn/mood
{"user":"stehrn","mood":"liking redis"}
``` 
The redis monitor should show something like this:
```
"DEL" "user:stehrn"
"HMSET" "user:stehrn" "_class" "com.github.stehrn.mood.Mood" "user" "stehrn" "mood" "liking redis"
"SADD" "user" "stehrn"
```  
Two keys are added: 
* `user:stehrn` maps to a hash data structure containing the mood dat; the [HMSET](https://redis.io/commands/hmset) command sets specified fields (`_class`, `user`, and `mood`) to their respective values. Note how redis also ensures any previous value is deleted via [DEL](https://redis.io/commands/del)  
* `user` maps to a set containing unique users, [SADD](https://redis.io/commands/sadd) is used to add an item the set 

Now, in a separate terminal, fire up a new spring boot process on a different port and verify we can get back the same mood for given user:
```
$ export SERVER_PORT=8095
$ mvn spring-boot:run
$ curl http://localhost:${SERVER_PORT}/user/stehrn/mood
{"user":"stehrn","mood":"liking redis"}
``` 

#### Back to redis-cli to check contents of store
Go back to the redis-cli terminal (come of of monitor using Ctrl-C), to list all keys: 
```
> keys *
1) "user:stehrn"
2) "user"
``` 
Get value for (hash) key `user:stehrn`:
```
> HGETALL user:stehrn 
1) "_class"
2) "com.github.stehrn.mood.Mood"
3) "user"
4) "stehrn"
5) "mood"
6) "liking redis"
```
See all users:
``` 
> SMEMBERS user
1) "stehrn"
``` 

#### Running demo app on docker
Now is a good time to put the Spring Boot app into a Docker container, before you start kill off any instances running from a terminal. 

Pre-requisite is a far jar has been created for the app:
```cmd
$ mvn package
```
The [Dockerfile](docker/Dockerfile) sources from the [openjdk 8 alpine](https://hub.docker.com/_/openjdk) image which is based on the lightweight Alpine Linux image, note how it also uses the [ENV](https://docs.docker.com/engine/reference/builder/#env) command to set `mood_not_found_message`:
```
# Alpine Linux with OpenJDK JRE
FROM openjdk:8-jre-alpine
# copy WAR into image
COPY demo-0.0.1-SNAPSHOT.jar /app.jar
# set env variable
ENV mood_not_found_message default for docker
# run application with this command line
CMD ["/usr/bin/java", "-jar", "-Dspring.profiles.active=default", "/app.jar"]
``` 
To [build](https://docs.docker.com/engine/reference/commandline/build/) a container image (called `mood-app`, tagged it with `latest`) from the Dockerfile, run the following commands:
```cmd
$ cd docker
$ docker build --file Dockerfile --tag mood-app:latest .
$ docker image ls mood-app
REPOSITORY          TAG                 IMAGE ID            CREATED             SIZE
mood-app            latest              2fe60c820c21        3 minutes ago       103MB
```
Note the size, its pretty big for such a simple app, we'll come back to that in a later post when looking at _disposability_

Next run a container based on the new image, give it the name `mood`, and publish its `8080` port so we can connect:
```cmd
$ docker run --name mood --detach --publish 8080:8080 mood-app:latest
$ docker logs mood --follow
```
Check new default mood not found message injected via `ENV` command is observed, note we need to pick a different user to the one we've been using (`stehrn`) since we've already set their mood and its been persisted into th redis server.  
```cmd
$ curl http://localhost:8080/user/josh/mood
{"user":"josh","mood":"default for docker"}
```
Lets override this with a value injected in at runtime, when the container starts using `--env`: 
```cmd
$ docker stop mood
$ docker rm mood
$ docker run --name mood --detach --publish 8080:8080 --env mood_not_found_message="runtime default for docker" mood-app:latest
$ curl http://localhost:8080/user/josh/mood
{... "status":404, "message":"runtime default for docker"}

```
That's as simple as containers get. So when do we we use one approach over the other for setting env variables? Creating the container image is something that generally happens at _build_ time. When we're ready to ship the latest version, a _release_ is created for one or more target envs. Given the application will need to be configured differently for each env, we dont want to encode anything into the binary, unless its a common default applicable to _all_ envs, otherwise best policy is to inject env variables in at runtime. 

Of course, passing in variables via the command line wont cut it in all but trivial apps, sourcing from files is another option (Docker support this), but lets hold off, since most real world apps wont be running docker directly, but rather a higher level container orchestration platform like kubernetes, which we'll come to in a bit and re-visit config.   
 

## (Factor 4) Backing Services - treat Backing Services as attached resources 

## (Factor 5) Build, release, run - strictly separate stages
 

xxx
## Dependencies 

#### Resources
We can take this a step further and think about dependencies to other resources and how to make sure they are available when an application runs. Things that belong to the application ecoystem, their lifecycle matches that of the app (think UML composite aggregation, the black diamond, if a composite is deleted, all other parts associated with it are deleted). A clue is based on _responsibility_, these are generally resources the app team are responsible for as well. This could include things like a database, distributed cache, or microservices; it would not include any shared services (e.g. logging or security) or third party dependencies. 

Possible ways to explicitly declare such dependencies include:
* Container dependencies - as noted above, TODO
* Infrastructure as code (IaC) - define infrastructure in a machine readable config that's version controlled. IaC model generates the same environment every time it is applied. Azure provides [quite a nice description of IaC](https://docs.microsoft.com/en-us/azure/devops/learn/what-is-infrastructure-as-code).
* Chef/Puppet - TODO    

## Config
kubernetes [configMaps](https://kubernetes.io/blog/2016/04/configuration-management-with-containers/) are great for storing, well, config, and we'll see this is action later.

It worth mentioning in passing [HashiCorp](https://www.hashicorp.com) products, popular on both cloud and container based platforms, [consul](https://www.consul.io) can be used for dynamic configuration, and [vault](https://www.hashicorp.com/products/vault) for storing secrets and passwords; most cloud providers also have their own equivalents with offerings like [Azure Key Vault](https://azure.microsoft.com/en-gb/services/key-vault/).   


xxx



## References
* https://www.ionos.com/community/hosting/redis/using-redis-in-docker-containers/
* https://docs.spring.io/spring-data/data-redis/docs/current/reference/html/#reference
* https://www.contino.io/insights/building-a-twelve-factor-application-part-one