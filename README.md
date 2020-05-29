# 12 factor apps in practice

This is a practical look at [12 factor](https://12factor.net) apps and how to build them for real. Its based on several years experience across a number of large financial organisations in teams with a strong focus on engineering and doing things well.     

A simple Java microservice app is evolved architecturally, from a single process app, to a [docker](https://www.docker.com) containerised distributed app, first running on a container orchestration platform ([kubernetes](https://kubernetes.io) and [OpenShift](https://www.openshift.com)), then on full-blown public cloud ([Azure](https://azure.microsoft.com/en-gb/)).

This is part one of a two part series covering about half the factors and all source is available in [twelve_factor](https://github.com/stehrn/twelve_factor) GitHub repo

Prerequisites:
* JDK and maven are installed (along with a decent IDE like [IntelliJ](https://www.jetbrains.com/idea/))
* Docker is [installed](https://docs.docker.com/get-docker/)

 
## Introducing the demo service
The demo service is the beginnings of a simple agile scrum _moodboard_, implemented as a microservice based architecture that has the following end-points to get and set the mood of the given user:
```
GET /user/<user>/mood/
PUT /user/<user>/mood/
```

The ([Spring Boot](https://spring.io/projects/spring-boot)) Java app was quick and easy to implement, helped by [Spring Initializr](https://start.spring.io) which generated a base maven project. 

Run [HttpRequestTest](src/test/java/com/github/stehrn/mood/HttpRequestTest.java) to test the 2 end points, or test via `curl`, bring up service first:
```cmd
$ mvn package
$ mvn spring-boot:run
```
...then set mood and retrieve:
```cmd
$ curl -X PUT -H "Content-Type: text/plain" -d "happy" http://localhost:8080/user/stehrn/mood 
$ curl http://localhost:8080/user/stehrn/mood
{"user":"stehrn","mood":"happy"}
```

## Dependencies 
[explicitly declare and isolate dependencies](https://12factor.net/dependencies) (12 factor)

#### Libraries
As engineers, most of us will know how to manage library dependencies (other binaries our code references either directly or indirectly) using tools like maven, gradle (beating maven in popularity), or Ivy for Java projects, Godep and Godm for Golang, and many more language specific tools. These are used to manage dependencies required by our application at build and runtime. 

The service uses maven for dependencies management, defined in `dependencies` section of the [pom.xm](pom.xml), for example:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```
(_the versions for this dependency, if you were wondering, are inherited from the parent pom_)

Dependencies with _provided_ scope are worth a special mention - these wont be packaged into the distribution, the assumption been they will be provided at runtime, so how then to define this in the runtime? This is where containers help, we'll come back to an example below where we define runtime dependencies inside a docker container.

TODO - have we done above? 

## Codebase 
[one codebase, many deploys](https://12factor.net/codebase) (12 factor)

The service lives in a single repo, and that repo only contains code for the moodboard service, nothing else.
  
#### What to do with shared code  
We don't have any code shared across yet since we only have one small app, but if we did, then time would be spent refactoring it out to a new repo, and referenced in the original app as a _library_ through dependency management. 
 
The new repo will have its own build and release process allowing the library and the library clients to evolve at their own pace. This is all about reducing the risk of breaking something and will generally save time in the long run. Just be aware the code been refactored out should have a different velocity of change unrelated to the app - if the app and library are frequently released together then they're too coupled and proably should not have been separated.

#### Versions 
Use [git-flow](https://nvie.com/posts/a-successful-git-branching-model/) and [feature branches](https://martinfowler.com/bliki/FeatureBranch.html) to support different versions of the app in the same repo. The demo project makes use of feature branches to demonstrate some of the enhancements (normally feature branches would be merged back to master and deleted to keep things trim).          

TODO: add link to branch

## Config 
[store config in the environment (not in the code)](https://12factor.net/config) (12 factor)

The initial version the the service has a bit of config defined in [application.properties](src/main/resources/application.properties) to set the HTTP not found/404 message when no mood set for user: 
```
mood_not_found_message=no mood
```
Its read in [MoodService](MoodService.java) via:
```java
@Value("${mood_not_found_message}")
private String moodNotFoundMessage;
```
So right now it appears its hard coded, to change the value requires us to rebuild the app. But what if we wanted to have different configurations based on the deployment environment (e.g. development/test/production or some other variant)?     

We're in luck, Spring Boot will detect _environment variables_ (treating them as properties), we just need to `export` the value before starting the process:
 ```cmd
$ export mood_not_found_message="no mood has been set for this user"
$ mvn spring-boot:run
$ curl http://localhost:8080/user/stehrn/mood
{... "status":404, "message":"no mood has been set for this user"}
```
Environment variables are easy to change and independent of programming language and development framework, and having configuration external to the code enables the same version of the binary to be deployed to each environment, just with different runtime configurations. 

Spring also provides _config as a service_ via [Spring Cloud Config](https://cloud.spring.io/spring-cloud-config/reference/html/) - this goes well beyond just storing config in the environment the process is running within - it enables the discovery of application properties via a service running on a different part of the network. It's an alternative option..._if_ you're using Spring, the other techniques we'll go through below are framework agnostic and therefore preferable.     

## Process and State
[execute the app as one or more stateless processes](https://12factor.net/processes) (12 factor)

State exists in the demo app, in the form of a simple in-process cache in [MoodService](src/main/java/com/github/stehrn/mood/MoodService.java) - so how to get this state out of the service process? The answer is to introduce a [backing service](https://12factor.net/backing-services) and store state there instead, backing services gets its own section below, for now you just need to know its any type of service the application consumes as part of its normal operation and is typically defined via a simple 'connection string' (think URL).  

Why bother? If we want to _scale out_ our process and create multiple instances to facilitate things like load balancing and application resilience, then having no state makes things much easier - just spin up another instance of the application process. 

So how do we do this for our app? Lets choose a cache - [redis](https://redis.io) is a good choice (alternatives might include Hazelcast or memcached), its described as "an in-memory data structure store, used as a database, cache and message broker ... supports data structures such as strings, hashes, lists, sets, ..." ([redis.io](https://redis.io)). Its a great bit of opensource with a strong community, so lets use it.

#### Running redis on docker
The quickest and easiest way to install and run redis is using docker, the [run](https://docs.docker.com/engine/reference/commandline/run/) command starts the redis container:
```cmd
$ docker run --name mood-redis --publish 6379:6379 --detach redis
```
`--publish` (`-p`) publishes the redis port so the service can connect it from outside of the container (more on this later).

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
Use the [`monitor`](https://redis.io/topics/rediscli#monitoring-commands-executed-in-redis) command to actively monitor the commands running against redis - it will just print out `OK` to begin with, we'll see more once the service is connected to redis. 

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
$ curl -X PUT -H "Content-Type: text/plain" -d "liking redis!" -i http://localhost:8080/user/stehrn/mood 
HTTP/1.1 200 

$ curl http://localhost:8080/user/stehrn/mood
{"user":"stehrn","mood":"liking redis!"}
``` 
The redis monitor should show something like this:
```
"DEL" "user:stehrn"
"HMSET" "user:stehrn" "_class" "com.github.stehrn.mood.Mood" "user" "stehrn" "mood" "liking redis!"
"SADD" "user" "stehrn"
```  
Two keys are added: 
* `user:stehrn` maps to a hash data structure containing the mood dat; the [HMSET](https://redis.io/commands/hmset) command sets specified fields (`_class`, `user`, and `mood`) to their respective values. Note how redis also ensures any previous value is deleted via [DEL](https://redis.io/commands/del)  
* `user` maps to a set containing unique users, [SADD](https://redis.io/commands/sadd) is used to add an item the set 

Now, in a separate terminal, fire up a new service process (on a different port to avoid a clash) and verify we can get back the same mood from the redis backing service for given user:
```
$ export SERVER_PORT=8095
$ mvn spring-boot:run
$ curl http://localhost:${SERVER_PORT}/user/stehrn/mood
{"user":"stehrn","mood":"liking redis!"}
``` 
Nice, so we now have two stateless services leveraging a redis backing service to store application state, nice!

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

The redis cache is an example of a backing service that was used to take state out of the application process, the cache is loosely coupled to the service, accessed via a simple URL defined through the `spring.redis.host` & `spring.redis.port` properties (default values in [application.properties](src/main/resources/application.properties) resolve to `localhost:6379`). 

The service knows nothing about the redis backing service - who owns it, how it was deployed, where its running - it might be running on the same node in a separate container, it might be a managed [Azure Cache for Redis](https://azure.microsoft.com/en-gb/services/cache/) service hosted in a different region. The point it, it doesn't matter, its a separation of concerns, each can be managed and deployed independently from each other.

The service deployment can be easily configured to use a different redis instance simply by changing some environment properties - nothing else needs to be done. Lets quickly bring up an alternative version of redis and attach it to the app, we'll use the [alpine version](https://hub.docker.com/_/redis/) of redis that has a smaller image size (note its bound to a different host port to avoid a port clash with the existing redis instance): 
```cmd
$ docker run --name mood-redis-alpine -p 6380:6379 -d redis:6.0.3-alpine
```
Now restart one of the two running service process in the terminal, but re-configure it to connect to the new version of redis simply by changing the URL (in this case just the redis port needs to be modified):
```cmd
$ mvn spring-boot:run -Dspring-boot.run.arguments=--spring.redis.port=6380
```
..and test as you did before (note since this is a brand new redis instance, it will have no data so you'll need to do a PUT then GET). 

So with no code changes, just a change to an environment property, we've managed to attach a completely different version of the cache backing service!

## Containers and dependencies
[explicitly declare and isolate dependencies](https://12factor.net/dependencies) (12 factor)

Lets revisit dependencies - we can manage build-time library dependencies declaratively using dependency management tools, but things are less clear when it comes to isolation of runtime dependencies - this is where containers can help since they provide: 
 
"... a standard unit of software that packages up code and all its dependencies so the application runs quickly and reliably from one computing environment to another ... a container image is a standalone, executable package of software that includes everything needed to run an application: code, runtime, system tools, system libraries and settings." ([docker.com](https://www.docker.com/resources/what-container))

We've seen this first hand with the redis container - the image had everything needed to run redis, nothing else was installed or configured. 

So lets ship the app inside a container so we have 100% certainty it has everything that's needed for it to run as expected (including any 'provided' scoped dependencies that can be dropped into the container), regardless of the runtime environment - I want the container I'm running and testing on my dev blade to work just the same way on a production cluster.  
 
## Running service on docker
The redis container needs a bit of a retrofit first since we're now in the world of inter-container communication (or networking) and need to configure things so the containers can communicate with each other.   

#### Retrofit redis container so service container can connect 
Lets make use of a user defined [bridge network](https://docs.docker.com/network/bridge/), its good security practice as ensures only related services/containers can communicate with each other.

Create a new network:
```cmd
$ docker network create mood-network
```
Restart redis, connecting the container to the `mood-network` network and giving it the `redis-cache` network alias (think of this as the 'hostname' the service container will use):
```cmd
$ docker rm --force mood-redis
$ docker run --name mood-redis --network mood-network --net-alias redis-cache -p 6379:6379 -d redis
``` 

#### Create and run service container
Now to create and run the service container - before starting kill off any service instances running from a terminal to avoid port clashes. 

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
To [build](https://docs.docker.com/engine/reference/commandline/build/) a container image (called `mood-app`, tagged it with `1.0.0`) from the Dockerfile, run the following commands:
```cmd
$ cd docker
$ docker build --file Dockerfile --tag mood-app:1.0.0 .
$ docker image ls mood-app
REPOSITORY          TAG                 IMAGE ID            CREATED             SIZE
mood-app            1.0.0              2fe60c820c21        3 minutes ago       103MB
```
Note the size, its pretty big for such a simple app, we'll come back to that when looking at _disposability_.

There's actually better ways to build the image, things have been kept simple here, but check out this [codefresh.io](https://codefresh.io/docker-tutorial/create-docker-images-for-java/) article that nicely summarised the pros and cons of different approaches. 

Next run a container named `mood-app` based on the new service image, publish its 8080 port so we can connect externally, and set the redis host property to the network alias of the `mood-redis` container: 
```cmd
$ docker run --name mood-app --network mood-network -p 8080:8080 --env spring.redis.host=redis-cache -d mood-app:1.0.0
```
At this point there are two containers running: a stateless (Spring Boot) service container and a redis container acting as a backing service. 

Check the new default message injected via `ENV` command is observed:  
```cmd
$ curl http://localhost:8080/user/stehrn/mood
{..., "user":"stehrn","mood":"default for docker"}
``` 
..and why not test setting mood again:
```cmd
$ curl -X PUT -H "Content-Type: text/plain" -d "liking containers!" -i http://localhost:8080/user/stehrn/mood 
$ curl http://localhost:8080/user/stehrn/mood
{"user":"stehrn","mood":"liking containers!"}
```
(if you're feeling adventurous, use `docker network inspect new-mood-network` to check out the bridge network and how the containers are connected to it)

## Port binding 
[Export services via port binding](https://12factor.net/port-binding)  (12 factor)

This has been touched on already when we had to "publish (our service) to port 8080 so we can connect to it" - the factor is relevant to _container based_ deployments, since without explicitly publishing a port nothing will be able to connect to the process running inside the container listening on that port. It does not have to be just the HTTP protocol, it can be _any_ type of service on a network - we've used another type service - the redis cache, using the redis protocol on port 6379.

As seen already, port binding in docker is achieved with the [`publish`](https://docs.docker.com/engine/reference/commandline/run/#publish-or-expose-port--p---expose) option:
```
--publish 8080:8080
``` 
Interpret this as `host:container`, this binds port 8080 of the container to TCP/HTTP port 8080 on the host machine, the Java process running inside the container is listening on port 8080, and the `publish` option will allow a connection to this port from outside of the container. The ports don't have to be the same, it's possible to publish to a different host port to the one the container process is listening to, you might do this if the other port was in use or you didn't want to reveal the internal container port number. 

Lets quickly test this for real:
```
$ docker run --name mood-app-ports --network mood-network -p 8085:8080 --env spring.redis.host=redis-cache -d mood-app:1.0.0
```
`-p 8085:8080` will expose port 8085 on the host and map to port 8080 on the container, inside the container the embedded web server starts up on (container) port 8080:
```
Tomcat started on port(s): 8080 (http)
```
...but externally we connect to the container process via its published 8085 port:
```
$ curl http://localhost:8085/user/stehrn/mood
```
Remember to clean up via `docker rm --force mood-app-ports`

So far we've been playing about in terminals using _localhost_, in a UAT or production deployment, a public facing hostname and port will be used with some routing to route requests onto a server process listening on a non public host/port - we'll see this in action when we deploy to Openshift.
### Publish versus expose
Its worth taking a step back and thinking about how we're currently connecting to the redis process - the service container is connecting to the redis container - i.e. container to container communication, so instead of publishing the redis port to the outside world we just need to expose it to the other container, this is safer right, from a security perspective, so lets kill the existing container and start with [`expose`](https://docs.docker.com/engine/reference/commandline/run/#publish-or-expose-port--p---expose) instead of `publish`:  
```cmd
$ docker rm --force mood-redis 
$ docker run --name mood-redis --network mood-network --net-alias redis-cache --expose 6379 -d redis
```
...and test as before.
 
## Containers and process execution
[execute the app as one or more stateless processes](https://12factor.net/processes) (12 factor)

Process and state has already been handled, making it much simpler to spin up the service process, but what about process _execution_ - how to reliably run the process with the required system resources and ensure the process remains healthy?   

This is where containers can help out again through the use of a higher level container orchestration framework like Kubernetes that enable the definition of the deployment topology - e.g. which services to deploy, how many (load-balanced) instances, their dependency to other services, and memory and CPU resource requirements. The orchestration framework will automatically maintain the desired state. 

Before looking into running on Kubernetes, lets touch on another great reason for using it...
 
## Declaring dependencies to other services
Lets come back to [explicitly declare and isolate dependencies](https://12factor.net/dependencies) (12 factor)

The service depends on the redis cache being available at runtime - how to ensure its running and available? The answer depends on how the application is architected. One style is the single node pattern - defined as groups of containers co-located on a single machine. This is the pattern used above already by starting two containers on the same (local developer) machine - its a pattern that would fit _if_ we were responsible for both the service and the redis cache. Lets assume we are responsible, so how do we manage their deployment? The answer, you guessed it, is an orchestration framework like Kubernetes.    
  
## Running containers on Kubernetes
Before proceeding, kill off anything that's running either in your terminals or in background on docker, that's legacy now, we're moving on up to container orchestration!
```cmd
$ TODO 
```  
Docker Desktop includes a standalone [Kubernetes server](https://docs.docker.com/get-started/orchestration/) that runs on your machine, we're going to use this to manage our containers, and the `kubectl` (pronounced “cube CTL”, “kube control”) command line interface to run commands against Kubernetes. 

Containers are scheduled as [`pods`](https://kubernetes.io/docs/concepts/workloads/pods/), which are groups of co-located containers that share some resources. Pods themselves are almost always scheduled as [`deployments`](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/), which are scalable groups of pods maintained automatically. 

All Kubernetes objects can are defined in YAML files that describe all the components and configurations of the app. [mood-app.yaml](docker/mood-app.yaml) defines our mood application, it has:
* [Deployment](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/), describing a scalable group of identical pods. In this case, you’ll get just one replica, or copy of your pod, and that pod (which is described under the template: key) has just one container in it, based off of your bulletinboard:1.0 image from the previous step in this tutorial.
* [NodePort](https://kubernetes.io/docs/concepts/services-networking/service/#nodeport) service, which will route traffic from port 8080 on your host to port 8080 inside the pods it routes to, allowing you to reach your bulletin board from the network.

TODO: change above

Deploy application to Kubernetes:
```cmd
$ kubectl apply -f mood-app.yaml
```
and check its ready:
```cmd
$ kubectl get deployments
NAME       READY   UP-TO-DATE   AVAILABLE   AGE
mood-app   1/1     1            1           19s
```
Logs can be tailed with:
```cmd
$ kubectl logs --selector=app=service --container mood-app --follow
```
(change `--container` (`-c`) to `mood-redis` to tail redis logs)

Once again test the service:
```cmd
$ curl -X PUT -H "Content-Type: text/plain" -d "happy" http://localhost:30001/user/stehrn/mood 
$ curl http://localhost:30001/user/stehrn/mood
{"user":"stehrn","mood":"happy"}
```

### Web Dashboard
A nice feature is the [web-ui-dashboard](https://kubernetes.io/docs/tasks/access-application-cluster/web-ui-dashboard/), install:
```cmd
$ kubectl apply -f https://raw.githubusercontent.com/kubernetes/dashboard/v2.0.0/aio/deploy/recommended.yaml
```
..and run:
```cmd
$ kubectl proxy
```
Open via [localhost link](http://localhost:8001/api/v1/namespaces/kubernetes-dashboard/services/https:kubernetes-dashboard:/proxy/.)

Use token from below command to log in: 
```cmd
$ kubectl -n kube-system describe secret default
```
..and you should see 
TODO: image

And tidy up by tearing down the application:
```cmd
$ kubectl delete -f mood-app.yaml
```  

Check out [deploy to Kubernetes](https://docs.docker.com/get-started/kube-deploy/) for more detail.

## Concurrency  
[Scale out via the process model](https://12factor.net/concurrency) (12 factor)

Adding more concurrency is a case of spinning up a new process - this is essentially horizontal scaling, a single JVM can only be increased so much until it hits the physical memory limits of the machine, so if you want to handle higher loads then the application must be able to span multiple processes running on multiple physical machines - i.e. scale out and become a distributed application.

Different processes can be assigned a type - HTTP requests may be handled by a web process, and long-running background tasks handled by a worker process, and different types of process can be scaled differently - e.g. you may have three load balanced web processes and ten worker processes.

Back to our app, we want to be able to scale it out, but how? Containers and container orchestration is the answer (again).

TODO: set replica count, or command to dynamically scale
 

 
 
 
 
 

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

