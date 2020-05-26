# 12 factor apps in practice

This is a practical look at [12 factor](https://12factor.net) apps and how to build them for real. Its based on several years experience across a number of large financial organisations in teams with a strong focus on engineering and doing things well.     

A simple Java microservice app is evolved architecturally, from a single process app, to a [docker](https://www.docker.com) containerised distributed app, first running on a container orchestration platform ([kubernetes](https://kubernetes.io) and [OpenShift](https://www.openshift.com)), then on full-blown public cloud ([Azure](https://azure.microsoft.com/en-gb/)).

This is part one of a two part series covering about half the factors and all source is available in [twelve_factor](https://github.com/stehrn/twelve_factor) GitHub repo
 
## Introducing the demo app
The demo app is the beginnings of a simple agile scrum _moodboard_, implemented as a microservice based architecture that has the following end-points to get and set the mood of the given user:
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

Dependencies with _provided_ scope are worth a special mention - these wont be packaged into your distribution, the assumption been they will be provided at runtime, so how then to define this in the runtime? This is where containers help, we'll come back to an example below where we define runtime dependencies inside a docker container. 

## Config 
[store config in the environment (not in the code)](https://12factor.net/config) (12 factor)

The initial version the the demo app has a bit of config defined in [application.properties](src/main/resources/application.properties) to set the HTTP not found/404 message when no mood set for user: 
```
mood_not_found_message=no mood
```
Its read in [MoodService](MoodService.java) via:
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

Spring also provides _config as a service_ via [Spring Cloud Config](https://cloud.spring.io/spring-cloud-config/reference/html/) - this goes well beyond just storing config in the environment the process is running within - it enables the discovery of application properties via a service running on a different part of the network. It's an alternative option..._if_ you're using Spring, the other techniques we'll go through below are framework agnostic and therefore preferable.     

## Process and State
[execute the app as one or more stateless processes](https://12factor.net/processes) (12 factor)

State exists in the demo app, in the form of a simple in-process cache in [MoodService](src/main/java/com/github/stehrn/mood/MoodService.java) - so how to get this state out of the app process? The answer is to introduce a [backing service](https://12factor.net/backing-services) and store state there instead, backing services gets its own section below, for now you just need to know its any type of service the app conusumes as part of its normal operation and is typically defined via a simple connection string.   

Why bother? If we want to _scale out_ our process and create multiple instances to facilitate things like load balancing and application resilience, then having no state makes things much easier - just spin up another instance of the application process. 

So how do we do this for our app? [redis](https://redis.io) is a good choice, defined on its website as "an in-memory data structure store, used as a database, cache and message broker ... supports data structures such as strings, hashes, lists, sets, ...". Its a great bit of opensource with a strong community, so lets use it; alternatives might include Hazelcast or memcached.

#### Running redis on docker
The quickest and easiest way to install and run redis is using docker, the [run](https://docs.docker.com/engine/reference/commandline/run/) command starts the redis container:
```cmd
$ docker run --name mood-redis --publish 6379:6379 --detach redis
```
`--publish` (`-p`) publishes the redis port so the app can connect it from outside of the container (more on this later).

`--detach` (`-d`) runs the container process in the background, to check the running process and tail the logs:
```cmd
$ docker ps
$ docker logs --follow mood-redis
```
The log should show the message `Ready to accept connections`, default logging does not actually tell us much, so lets get a bit more interactive using the redis command line interface [(redis-cli)](https://redis.io/topics/rediscli). 

To access redis-cli via docker, open an interactive (`it`) shell against the running redis container and run the `redis-cli` command:  
```cmd
$ docker exec -it mood-redis sh -c redis-cli 
``` 
Use the [`monitor`](https://redis.io/topics/rediscli#monitoring-commands-executed-in-redis) command to actively monitor the commands running against redis - it will just print out `OK` to begin with, we'll see more once the app is connected to redis. 

#### Connecting Spring Boot to redis
Lets replace the existing in memory cache with a redis cache using [Spring Data Redis](https://docs.spring.io/spring-data/data-redis/docs/current/reference/html/#reference), not many changes are required:  
* [`RedisConfig`](src/main/java/com/github/stehrn/mood/RedisConfig.java) has been added to provide relevant redis configuration information for Spring to connect to redis, it includes a `RedisTemplate` Spring Data bean uses to interact with the Redis server and a `RedisConnectionFactory`
* The addition of `@RedisHash("user")` to [`Mood`](src/main/java/com/github/stehrn/mood/Mood.java) tells Spring to store the mood entity in redis and not its default `KeyValue` store 

Start app:
```
mvn spring-boot:run
```
...and set a new mood
```
$ curl -X PUT  -H "Content-Type: text/plain" -d "liking redis" -i http://localhost:8080/user/stehrn/mood 
HTTP/1.1 200 

$ curl http://localhost:8080/user/stehrn/mood
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

Now, in a separate terminal, fire up a new app process (on a different port to avoid a clash) and verify we can get back the same mood from the redis backing service for given user:
```
$ export SERVER_PORT=8095
$ mvn spring-boot:run
$ curl http://localhost:${SERVER_PORT}/user/stehrn/mood
{"user":"stehrn","mood":"liking redis"}
``` 
So we now have two stateless app services leveraging a redis backing service to store application state, nice!

#### Sidebar: Back to redis-cli to check contents of store
Go back to the redis-cli terminal (come out of monitor using Ctrl-C), to list all keys: 
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

## Backing Services  
[treat backing services as attached resources](https://12factor.net/backing-services) (12 factor)

A backing service is any service the app consumes over the network as part of its normal operation - the redis cache is an example of a backing service that was used to take state out of the application process, the cache is loosely coupled to the mood app, accessed via a simple URL defined through the `spring.redis.host` & `spring.redis.port` properties (default values in [application.properties](src/main/resources/application.properties) resolve to `localhost:6379`). 

The app knows nothing about the redis backing service - who owns it, how it was deployed, where its running - it might be running on the same node in a separate container, it might be a managed [Azure Cache for Redis](https://azure.microsoft.com/en-gb/services/cache/) service hosted in a different region. The point it, it doesn't matter, its a separation of concerns, each can be managed and deployed independently from each other.

The app deployment can be easily configured to use a different redis instance simply by changing some environment properties - nothing else needs to be done. Lets quickly bring up an alternative version of redis and attach it to the app, we'll use the [alpine version](https://hub.docker.com/_/redis/) of redis that has a smaller image size (note its bound to a different host port to avoid a port clash with the existing redis instance): 
```cmd
$ docker run --name mood-redis-alpine -p 6380:6379 -d redis:6.0.3-alpine
```
Now restart one of the two running app process in the terminal, but re-configure it to connect to the new version of redis simply by changing the URL (in this case just the redis port needs to be modified):
```cmd
$ mvn spring-boot:run -Dspring-boot.run.arguments=--spring.redis.port=6380
```
..and test as you did before, note since this is a brand new redis instance, it will have no data so you'll need to do a PUT then GET.

## Containers and dependencies
[explicitly declare and isolate dependencies](https://12factor.net/dependencies) (12 factor)

Lets revisit dependencies - we can manage build-time library dependencies declaratively, but things are less clear with runtime dependencies; this is where containers can help as rthey provide: 
 
"... a standard unit of software that packages up code and all its dependencies so the application runs quickly and reliably from one computing environment to another ... a container image is a lightweight, standalone, executable package of software that includes everything needed to run an application: code, runtime, system tools, system libraries and settings." [docker.com](https://www.docker.com/resources/what-container)

We've seen this first hand with the redis container - the image had everything needed to run redis - we did not need to install or configure anything else. So lets ship our app inside a container so that we have 100% certainty it has everything that's needed for it to run as expected - including provided scoped dependencies!  There's more as well.. 

## Containers and process execution
[execute the app as one or more stateless processes](https://12factor.net/processes) (12 factor)

Process and state has already been handled, making it much simpler to spin up the app process by storing state in a backing service, but what about process _execution_ - how to reliably run the process with the requires system resources and ensure the process remains healthy?   

This is where containers can help out again through the use of a higher level container orchestration framework like Kubernetes, that will enable the definition of a deployment topology which will be automatically maintained. So we can define in config (typically yaml) which container images to run, how many instances (replicas)  and what resources are required - in our case we may want 3 load-balanced instances of the app with 300MB of available memory - the framework will do everything it can to ensure what we want is what we have.       

We'll see how to do this for real once the app is run inside a (docker) container. 
 
#### Running mood app on docker
The redis container needs a bit of a retrofit first since we're now in the world of inter-container communication (or networking) and need to configure things so the containers can communicate with each other.   

##### Retrofit redis container so app container can connect 
Lets make use of a user defined [bridge network](https://docs.docker.com/network/bridge/), its good security practice as ensures only related services/containers can communicate with each other.

Create a new network:
```cmd
$ docker network create mood-network
```
Restart redis, connecting the container to the `mood-network` network and giving it the `redis-cache` network alias (think of this as the 'hostname' the app container will use):
```cmd
$ docker rm --force mood-redis
$ docker run --name mood-redis --network mood-network --net-alias redis-cache -p 6379:6379 -d redis
``` 

##### Create and run app container
Now to create and run the app container - before starting kill off any app instances running from a terminal to avoid port clashes. 

Pre-requisite is that a far jar has been created for the app:
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
Note the size, its pretty big for such a simple app, we'll come back to that when looking at _disposability_.

There's actually better ways to build the image, things have been kept simple here but this article [TODO] TODO. 

Next run a container named `mood-app` based on the new app image, publish its 8080 port so we can connect externally, and set the redis host property to the network alias of the `mood-redis` container: 
```cmd
$ docker run --name mood-app --network mood-network -p 8080:8080 --env spring.redis.host=redis-cache -d mood-app:latest
```
At this point, we have 2 containers running, a stateless (Spring Boot) app container and a redis container acting as a backing service; check new default mood not found message injected via `ENV` command is observed:  
```cmd
$ curl http://localhost:8080/user/stehrn/mood
{..., "user":"stehrn","mood":"default for docker"}
``` 

TODO: improve above?

TODO: this may be set from before?

TODO: docker network inspect new-mood-network

## Port binding 
[Export services via port binding](https://12factor.net/port-binding)  (12 factor)

We've touched on this already when we had to "publish (our service) to port 8080 so we can connect to it" - the factor is relevant to _container based_ deployments, since without explicitly publishing a port, you wont be able to connect to any process running inside the container listening on that port. It does not have to be just the HTTP protocol, it can be _any_ type of service on a network - we've used another type service, the redis cache using the redis protocol on port 6379.

As we've seen, port binding in docker is achieved with the [`publish`](https://docs.docker.com/engine/reference/commandline/run/#publish-or-expose-port--p---expose) option, for the app we achieved port binding with:
```
--publish 8080:8080
``` 
Interpret this as `host:container`, this binds port 8080 of the container to TCP/HTTP port 8080 on the host machine, the Java process running inside the container is listening on port 8080, and the `publish` option will allow a connection to this port from outside of the container. The ports don't have to be the same, it's possible to publish to a different host port to the one the container process is listening to, you might do this if the other port was in use or you didn't want to reveal the "real" port number. Lets quickly test this for real:
```
$ docker rm --force mood-app
$ docker run --name mood-app --network mood-network -p 8085:8080 --env spring.redis.host=redis-cache -d mood-app:latest
```
`-p 8085:8080` will expose port 8085 on the host and map to port 8080 on the container, inside the container the embedded web server starts up on (container) port 8080:
```
Tomcat started on port(s): 8080 (http)
```
...but we externally connect to the container process via its published port 8085:
```
$ curl http://localhost:8085/user/stehrn/mood
```
So far we've been playing about in terminals using _localhost_, in a UAT or production deployment, a public facing hostname and port will be used with some routing to route requests onto a server process listening on a non public host/port - we'll see this in action when we deploy to Openshift.
### Publish versus expose
Its worth taking a step back and thinking about how we're currently connecting to the redis process - the app container is connecting to the redis container - i.e. container to container communication, so instead of publishing the redis port to the outside world we just need to expose it to the other container, this is safer right, from a security perspective, so lets kill the existing container and start with [`expose`](https://docs.docker.com/engine/reference/commandline/run/#publish-or-expose-port--p---expose) instead of `publish`:  
```cmd
$ docker rm --force mood-redis 
$ docker run --name mood-redis --network mood-network --net-alias redis-cache --expose 6379 -d redis
```
...and test:
```cmd
$ curl http://localhost:8085/user/stehrn/mood
```
 
## Concurrency  
[Scale out via the process model](https://12factor.net/concurrency) (12 factor)

Adding more concurrency is a case of spinning up a new process - this is essentially horizontal scaling, a single JVM can only be increased so much until it hits the physical memory limits of the machine, so if you want to handle higher loads then the application must be able to span multiple processes running on multiple physical machines - i.e. scale out and become a distributed application.

Different processes can be assigned a type - HTTP requests may be handled by a web process, and long-running background tasks handled by a worker process, and different types of process can be scaled differently - e.g. you may have three load balanced web processes and ten worker processes.

Back to our app, we want to be able to scale it out, but how? Containers and container orchestration is the answer.

TODO: more detail here.

We've already made it simpler to spin up the app process by using a backing service to take away state, the next step is to run it in a container...      

 
## Dependencies
Lets come back to [explicitly declare and isolate dependencies](https://12factor.net/dependencies) (12 factor)

The mood app depends on the redis cache defined through the backing service URL being available at runtime. How do we do this? The answer depends on how the application is architected. One style is the _single node_ pattern - defined as groups of containers co-located on a single machine. This is the pattern we used above already by starting two containers on the same machine, but that involved manually running docker commands, how do we encode this into something that explicitly defines the target state? 

TODO: docker
https://docs.docker.com/compose/    


## (Factor 5) Build, release, run - strictly separate stages
 


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

Sign up for free here.
https://www.openshift.com/products/online/

- 2GiB memory for your applications
- 2GiB persistent storage for your applications
- 60-day duration



### Env Variables
So which approach to use for setting env variables? Creating the container image is something that generally happens at _build_ time. When we're ready to ship the latest version, a _release_ is created for one or more target envs. Given the application will need to be configured differently for each env, we dont want to encode anything into the binary, unless its a common default applicable to _all_ envs, otherwise best policy is to inject env variables in at runtime. 

Of course, passing in variables via the command line wont cut it in all but trivial apps, sourcing from files is another option (Docker supports this), but lets hold off, since most real world apps wont be running docker directly, but rather a higher level container orchestration platform like kubernetes, which we'll come to in a bit and re-visit config.   


xxx


"..on port 8080 on the Docker host, so external clients can access that port"





docker network inspect new-mood-network

xxx
