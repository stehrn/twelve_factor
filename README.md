# 12 factor apps in practice

This is a practical look at [12 factor](https://12factor.net) apps and how to build them for real. 

A simple Java microservice is evolved architecturally, from a single process app, to a multi process [Docker](https://www.docker.com) containerised app, first running on the container orchestration platform [Kubernetes](https://kubernetes.io), and then rolled out to Red Hat [OpenShift](https://www.openshift.com).

Its hands on, with all source available in [twelve_factor](https://github.com/stehrn/twelve_factor) GitHub repo, technical prerequisites:
* JDK and maven are installed (along with a decent IDE like [IntelliJ](https://www.jetbrains.com/idea/))
* Docker is [installed](https://docs.docker.com/get-docker/)

Take a bit of time up-front to read the introductions to Docker containers and Kubernetes since these are used heavily and a basic understanding will help, whilst lots of links to more specifics are scattered throughout the blog.    
 
## Introducing the mood service
The mood service is the beginnings of a simple agile scrum _moodboard_, its a middle tier that exposes the following RESTful end-points to get and set the mood of the given user:
```
GET /mood/user/<user>
PUT /mood/user/<user> <mood>
```

The ([Spring Boot](https://spring.io/projects/spring-boot)) Java app was quick and easy to implement, helped by [Spring Initializr](https://start.spring.io) which generated a base maven project. 

First go back in time to initial version of the app:
```cmd
$ git checkout tags/in_memory -b master
```

Run [HttpRequestTest](src/test/java/com/github/stehrn/mood/HttpRequestTest.java) to test the 2 end points, or test via `curl`, bring up service first, from the terminal:
```cmd
$ mvn package
$ mvn spring-boot:run
```
...then (in a separate terminal) set mood for a user (`stehrn`) and retrieve:
```cmd
$ curl -X PUT -H "Content-Type: text/plain" -d "happy" http://localhost:8080/user/stehrn/mood 
$ curl http://localhost:8080/user/stehrn/mood
{"user":"stehrn","mood":"happy"}
```

## Dependencies 
[explicitly declare and isolate dependencies](https://12factor.net/dependencies) (12 factor)

#### Libraries
If you're an engineer, you'll likely know how to manage library dependencies (other binaries code references either directly or indirectly) using tools like maven, gradle (starting to beat maven in popularity), or Ivy for Java projects, Godep and Godm for Golang, and many more language specific tools. These are used to manage binary dependencies required by an application at build and runtime. 

The (mood) service uses [Apache maven](http://maven.apache.org) for dependencies management, defined in `dependencies` section of the [pom.xm](pom.xml), for example:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```
(_the version for this dependency, if you were wondering, is inherited from the parent pom_)

maven will download this dependency to a local repo and add it to the classpath when compiling the source code; when packaging the project for deployment the binary will be added to the fat jar (this [series of articles](https://cguntur.me/2020/05/23/understanding-apache-maven-part-1/) provides a good overview of maven).
 
## Codebase 
[one codebase, many deploys](https://12factor.net/codebase) (12 factor)

The service lives in a single repo, and that repo only contains code for the mood service, nothing else.
  
#### What to do with shared code  
Given this is a small demo app, no code is shared yet, but if it was, then time would be spent refactoring it out as a _library_ into a new repo, releasing a version to a binary repository, and referencing those binaries in the original app through the maven dependency management framework. 
 
The shared library will have it's own build and release process, allowing the library and applications dependant on the library to evolve at their own pace. This is all about reducing the risk of breaking something in the library clients and will generally save time in the long run. Just be aware the code been refactored out should have a different velocity of change unrelated to the app - if the app and library are frequently released together then they're too coupled and probably should not have been separated.

#### Versions 
Use [git-flow](https://nvie.com/posts/a-successful-git-branching-model/) and [feature branches](https://martinfowler.com/bliki/FeatureBranch.html) to support different versions of the app in the same repo. The demo project makes use of feature branches to demonstrate some of the enhancements (normally feature branches would be merged back to master and deleted to keep things trim).          

TODO: git command to view branches?

## Config 
[store config in the environment (not in the code)](https://12factor.net/config) (12 factor)

The initial version the the service has a bit of config defined in [application.properties](src/main/resources/application.properties) to set the HTTP not found/404 message when no mood is set for user: 
```
mood_not_found_message=no mood
```
[MoodService](MoodService.java) reads the property using the [`@Value`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/beans/factory/annotation/Value.html) annotation:
```java
@Value("${mood_not_found_message}")
private String moodNotFoundMessage;
```
Right now it appears it's hard coded in the properties file, to change the value requires a rebuild of the app. But what if we wanted to have different configurations based on the deployment environment (e.g. development/test/production or some other variant)?     

We're in luck, Spring Boot will detect _environment variables_ (treating them as properties), we just need to `export` the value before starting the process:
 ```cmd
$ export mood_not_found_message="no mood from environment"
$ mvn spring-boot:run
$ curl http://localhost:8080/user/stehrn/mood
{... "status":404, "message":"no mood from environment"}
```
Environment variables are easy to change and independent of programming language and development framework, and having configuration external to the code enables the same version of the binary to be deployed to each environment, just with different runtime configurations. 

Spring also provides _config as a service_ via [Spring Cloud Config](https://cloud.spring.io/spring-cloud-config/reference/html/) - this goes well beyond just storing config in the environment the process is running within - it enables the discovery of application properties via a service running on a different part of the network. It's an alternative option..._if_ you're using Spring, the other techniques we'll go through below are framework agnostic and therefore preferable.     

## Process and State
[execute the app as one or more stateless processes](https://12factor.net/processes) (12 factor)

State exists in the mood service, in the form of a simple in-process cache in [MoodService](src/main/java/com/github/stehrn/mood/MoodService.java). If we want to _scale out_ our process and create multiple instances to facilitate things like load balancing and application resilience, then having no state makes things much easier - just spin up another instance of the application process, with no need for the added complexities of ensuring cache coherence across replicated versions of the data in each process.  

So how to get state out of the service process? The answer is to introduce a [backing service](https://12factor.net/backing-services) and store state there instead, backing services gets it's own section below, for now you just need to know it's any type of service the application consumes as part of it's normal operation and is typically defined via a simple 'connection string' (think URL).  

Lets choose a distributed cache - [redis](https://redis.io) is a good choice (alternatives might include Hazelcast or memcached), its an opensource project with a strong community, described as "an in-memory data structure store, used as a database, cache and message broker ... supports data structures such as strings, hashes, lists, sets, ..." ([redis.io](https://redis.io)). 

#### Running redis on docker
The quickest and easiest way to install and run redis is using docker, the [run](https://docs.docker.com/engine/reference/commandline/run/) command starts the redis container:
```cmd
$ docker run --name mood-redis --publish 6379:6379 --detach redis
```
The redis port is published (`--publish`/`-p`) so that the mood service can connect to it from outside of the container (more on this later).

To free up the terminal the container process has been run in the background (`--detach`/`-d`), to check the running process and tail the logs:
```cmd
$ docker ps
$ docker logs --follow mood-redis
```
The log should show the message `Ready to accept connections`, default logging does not actually tell us much, so lets get a bit more interactive using the redis command line interface [(redis-cli)](https://redis.io/topics/rediscli). 

To access redis-cli via docker, open an interactive (`-it`) shell (`sh`) against the running redis container and run the `redis-cli` command (`-c`):  
```cmd
$ docker exec -it mood-redis sh -c redis-cli 
``` 
Use the [`monitor`](https://redis.io/topics/rediscli#monitoring-commands-executed-in-redis) command to actively monitor the commands running against redis - it will just print out `OK` to begin with, we'll see more once the service is connected to redis. 

#### Connecting Spring Boot to redis
Lets replace the existing in memory cache with a redis cache using [Spring Data Redis](https://docs.spring.io/spring-data/data-redis/docs/current/reference/html/#reference).

Go back to terminal the app is running in, stop it (`Ctrl-C`), and fast forward to version of the app that has redis configured: 
```cmd
$ git checkout TODO
$ mvn clean package
```

Not many changes were required to add the redis cache:  
* [`RedisConfig`](src/main/java/com/github/stehrn/mood/RedisConfig.java) provides configuration information for Spring to connect to redis, including a `RedisTemplate` (the Spring Data bean uses to interact with the Redis server) and a `RedisConnectionFactory`
* The addition of `@RedisHash("user")` to [`Mood`](src/main/java/com/github/stehrn/mood/Mood.java) tells Spring to store the mood entity in redis and not it's default in memory store 

Start the app again:
```
$ mvn spring-boot:run
```
...and set a new mood
```
$ curl -X PUT -H "Content-Type: text/plain" -d "liking redis" -i http://localhost:8080/user/stehrn/mood 
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
* `user:stehrn` maps to a hash data structure containing the mood data; the [HMSET](https://redis.io/commands/hmset) command sets specified fields (`_class`, `user`, and `mood`) to their respective values. Note how redis also ensures any previous value is deleted via [DEL](https://redis.io/commands/del)  
* `user` maps to a set containing unique users, [SADD](https://redis.io/commands/sadd) is used to add an item the set 

Now, in a separate terminal, fire up a new service process on a different port to avoid a clash:
```
$ export SERVER_PORT=8095
$ mvn spring-boot:run
```
...and verify the same user mood can be sourced from the redis backing service:
```cmd
$ curl http://localhost:8095/user/stehrn/mood
{"user":"stehrn","mood":"liking redis!"}
```
 
We now have two stateless services leveraging a redis backing service to store application state, nice!

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

The service knows nothing about the redis backing service - who owns it, how it was deployed, where it's running - it might be running on the same node in a separate container, it might be a managed [Azure Cache for Redis](https://azure.microsoft.com/en-gb/services/cache/) service hosted in a different region. The point it, it doesn't matter, it's a separation of concerns, each can be managed and deployed independently from each other.

The service deployment can be easily configured to use a different redis instance simply by changing some environment properties - nothing else needs to be done. Lets quickly bring up an alternative version of redis and attach it to the app, we'll use the [alpine version](https://hub.docker.com/_/redis/) of redis that has a smaller image size (note it's bound to a different host port to avoid a port clash with the existing redis instance): 
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

Lets revisit dependencies - some types of dependency (e.g. maven [_provided_](https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#Dependency_Scope) scope) wont be packaged into the distribution by the dependency framework, the assumption been they will be (fingers crossed) available at runtime, so how to be sure they are available at runtime? This is where containers can help by providing: 
 
"... a standard unit of software ... that runs quickly and reliably from one computing environment to another ... includes everything needed to run an application: code, runtime, system tools, system libraries and settings." ([docker.com](https://www.docker.com/resources/what-container))

We've seen this first hand with the redis container - the image had _everything_ needed to run redis, nothing else was installed or configured on the target host. 

What extra dependencies does the mood service have? Well, there's a system library dependency on the Java 1.8 runtime (JDK). So lets ship the app inside a container so we have 100% certainty it has everything that's needed for it to run as expected, regardless of the runtime environment - the container been run and tested on a dev blade should work just the same way on a production cluster.  
 
## Running service on docker
The redis container needs a bit of a retrofit first since we're now in the world of inter-container communication (networking) and need to configure things so the containers can communicate with each other.   

#### Retrofit redis container so service container can connect 
Lets make use of a user defined [bridge network](https://docs.docker.com/network/bridge/), it's good security practice as ensures only related services/containers can communicate with each other.

Create a new network:
```cmd
$ docker network create mood-network
```
Restart the redis container, connecting it to the `mood-network` network and giving it the `redis-cache` network alias (think of this as the 'hostname' the service container will use):
```cmd
$ docker rm --force mood-redis
$ docker run --name mood-redis --network mood-network --net-alias redis-cache -p 6379:6379 -d redis
``` 

#### Create and run service container
Now to create and run the service container - before starting, kill off any service instances running from a terminal to avoid port clashes. 

Pre-requisite is that a far jar has been created for the app:
```cmd
$ mvn clean package
```
The [Dockerfile](docker/Dockerfile) sources from the [openjdk 8 alpine](https://hub.docker.com/_/openjdk) image which is based on the lightweight Alpine Linux image, note how it also uses the [ENV](https://docs.docker.com/engine/reference/builder/#env) instruction to set `mood_not_found_message`:
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
See [Docker instructions](https://docs.docker.com/engine/reference/builder/) for more detail.

To [build](https://docs.docker.com/engine/reference/commandline/build/) the container image (called `mood-service`, tagged it with `1.0.0`) from the Dockerfile, run the following commands:
```cmd
$ cd docker
$ docker build --tag mood-service:1.0.0 .
$ docker image ls mood-service
REPOSITORY          TAG                 IMAGE ID            CREATED             SIZE
mood-service        1.0.0               b06d5bb9f799        28 seconds ago      116MB
```
Note the size, it's pretty big for such a simple app, we'll come back to that when looking at _disposability_.

There's actually better ways to build the image, things have been kept simple here, but check out this [codefresh.io](https://codefresh.io/docker-tutorial/create-docker-images-for-java/) article that nicely summarised the pros and cons of different approaches. 

Next run a container named `mood-service` based on the new image, publish it's 8080 port so we can connect externally, and set the redis host property to the network alias of the `mood-redis` container: 
```cmd
$ docker run --name mood-service --network mood-network -p 8080:8080 --env spring.redis.host=redis-cache -d mood-service:1.0.0
```
At this point there are two containers running: a stateless (Spring Boot) service container and a redis container acting as a backing service. 

Check the new default message injected via `ENV` command is observed:  
```cmd
$ curl http://localhost:8080/user/stehrn/mood
{..., "user":"stehrn","mood":"default for docker"}
``` 
..and why not test setting the mood again:
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
Interpret this as `host:container`, this binds port 8080 of the container to TCP/HTTP port 8080 on the host machine, the Java process running inside the container is listening on port 8080, and the `publish` option will allow a connection to this port from outside of the container. The ports don't have to be the same, it's possible to publish to a different host port to the one the container process is listening to, you might do this if the other port was in use or you didn't want to reveal the internal container port. 

Lets quickly test this for real by bringing up a service container listening on a different host port:
```
$ docker run --name mood-service-ports --network mood-network -p 8085:8080 --env spring.redis.host=redis-cache -d mood-service:1.0.0
```
`-p 8085:8080` will expose port 8085 on the host and map to port 8080 on the container, inside the container the embedded web server starts up on (container) port 8080:
```
Tomcat started on port(s): 8080 (http)
```
...but externally we connect to the container process via it's published 8085 port:
```
$ curl http://localhost:8085/user/stehrn/mood
```
Remember to clean up with `docker rm --force mood-service-ports`

So far we've been playing about in terminals using _localhost_, but in a UAT or production deployment, a public facing hostname and port will be used with some routing to route requests onto a server process listening on a non public host/port - we'll see this in action when we deploy to Openshift.

### Publish versus expose
it's worth taking a step back and thinking about how we're currently connecting to the redis process - the service container is connecting to the redis container - i.e. container to container communication, so instead of publishing the redis port to the outside world we just need to expose it to the other container, this is safer from a security perspective, so lets kill the existing container and start with [`expose`](https://docs.docker.com/engine/reference/commandline/run/#publish-or-expose-port--p---expose) instead of `publish`:  
```cmd
$ docker rm --force mood-redis 
$ docker run --name mood-redis --network mood-network --net-alias redis-cache --expose 6379 -d redis
```
...and test as before.
 
## Declaring dependencies to other services
Lets come back to [explicitly declare and isolate dependencies](https://12factor.net/dependencies) (12 factor)

The service depends on the redis cache being available at runtime - how to ensure it's running and available? If we assume we are responsible for both the service and the redis cache, then we can manage their deployment and runtime operation using an orchestration framework like Kubernetes to assemble the service and redis containers and supporting infrastructure into a complete application.

How we define the service and redis cache depends on how the application is architected. The simplest of distributed system patterns is the _single node_ pattern - defined as groups of containers co-located on a single machine. There are good use cases for this pattern - the [sidecar](https://docs.microsoft.com/en-us/azure/architecture/patterns/sidecar) pattern is a good example. 

Do we want to run a cache on the same node/host as the service itself? Kubernetes allows each container to have its own guarantees around resource (CPU/memory) availability, but they'd still be on the same node, which raises concerns around availability - if the node goes down, everything goes down. Another concern is scalability, we can't easily scale if the containers are deployed together. Reliability, scalability and separation of concerns dictate that the application should be built out across multiple nodes - a _multi node_ pattern, and this is what we'll do. 
   
## Running application on Kubernetes
Lets look at running the service and redis containers on Kubernetes. Before proceeding, kill off anything that's running either in your terminals or in background on docker, that's legacy now, we're moving on up to container orchestration!

Docker Desktop includes a standalone [Kubernetes server](https://docs.docker.com/get-started/orchestration/) that runs on your machine, we're going to use this to manage the containers, and the `kubectl` (pronounced “cube CTL”, “kube control”) command line interface to run commands against Kubernetes. 

Containers are scheduled as [`pods`](https://kubernetes.io/docs/concepts/workloads/pods/), which are groups of co-located containers that share some resources. Pods themselves are almost always scheduled as [`deployments`](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/), which are scalable groups of pods maintained automatically. A [service](https://kubernetes.io/docs/concepts/services-networking/service) abstraction defines a logical set of pods and a policy by which to access them - pods are mortal and can be restarted (e.g. if the process inside the container becomes non responsive), a service provides a consistent means for a client to access a pod.

We decided to run things as a multi node pattern, this is achieved by putting the containers into separate deployments.    

All Kubernetes objects can be defined in YAML files, [mood-app.yaml](docker/mood-app.yaml) describe all the components and configurations of the app, it has the following objects for redis:

* [Deployment](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/) with a single container derived from the `redis:6.0.3-alpine` image  
* [ClusterIP (default) service](https://kubernetes.io/docs/concepts/services-networking/service/) exposing port 6379 - ClusterIP makes the service only reachable from within the cluster, which is all we need for mood service to connect to redis

.. and a similar set of objects for the mood service:
* Deployment of mood service using `mood-service:1.0.0` image
* [NodePort service](https://kubernetes.io/docs/concepts/services-networking/service/#nodeport), which will route traffic from port 8080 on the host to port 8080 inside the pods it routes to


Lets deploy the application to Kubernetes:
```cmd
$ kubectl apply -f mood-app.yaml
```
and check it's ready:
```cmd
$ kubectl get deployments
NAME           READY   UP-TO-DATE   AVAILABLE   AGE
mood-redis     1/1     1            1           3s
mood-service   1/1     1            1           3s
```
Logs can be tailed with:
```cmd
$ kubectl logs -l app=service --follow 
```
(change `-l` to `app=redis` to tail redis logs)

Once again test the service (note different port):
```cmd 
$ curl -X PUT -H "Content-Type: text/plain" -d "happy with Kubernetes" http://localhost:30001/user/stehrn/mood 
$ curl http://localhost:30001/user/stehrn/mood
{"user":"stehrn","mood":"happy with Kubernetes"}
```

### Web Dashboard
A nice feature of Kubernetes is the [web-ui-dashboard](https://kubernetes.io/docs/tasks/access-application-cluster/web-ui-dashboard/), its easy to install:
```cmd
$ kubectl apply -f https://raw.githubusercontent.com/kubernetes/dashboard/v2.0.0/aio/deploy/recommended.yaml
```
..and run:
```cmd
$ kubectl proxy
```
Open via [localhost link](http://localhost:8001/api/v1/namespaces/kubernetes-dashboard/services/https:kubernetes-dashboard:/proxy/.) and use token from below command to log in: 
```cmd
$ kubectl -n kube-system describe secret default
```
..and you should see 
TODO: image

## Orchestration and process execution
[execute the app as one or more stateless processes](https://12factor.net/processes) (12 factor)

Process and state has already been handled, making it much simpler to spin up the service (and cache) process, we've just used Kubernetes to do this, but lets dig a bit more into process execution.  

### Defining resources available to a process
Kubernetes provides a few more features to enable the reliable execution of the container process by allowing the definition of memory and CPU resource requirements - we want the container to have enough resources to actually run!

The following [requests and limits](https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/#requests-and-limits) were set on the service container to tell Kubernetes how much CPU and memory we expect the application to use. 
```cmd
      containers:
        - name: mood-service
          image: mood-service:1.0.0
          resources:
            requests:
              cpu: 500m
              memory: 512Mi
            limits:
              cpu: 800m
              memory: 1Gi
``` 
(CPU resources are defined in millicores - 500m means we want 50% of a core)

The _resource request_ is used by Kubernetes to decide which node to place the Pod on, one that has these resources available (it also uses this information to do some clever packing of processes on the cluster to ensure workloads are spread evenly).   

The _resource limit_ is enforced, the running container is not allowed to use more of that resource than set by the limit, ensuring a badly behaved process (e.g. hogging 100% CPU) does not impact other processes on the cluster. Limits should be set such that they are only reached in exceptional circumstances - e.g. to kill off a process that might have a memory leak. 
 
Set the CPU limit too low and Kubernetes will soon start throttling the container (CPU will be artificially restricted) giving impression the application is performing really badly!      

The config for the mood-service container is stating we want the container to run on a node that can provide ~0.5 GB of memory and access to 50% of the CPU time, and to kill the container if it uses more than 1GB of memory and throttle it if CPU usage rises above 80%.

Check out this good [google gcp article](https://cloud.google.com/blog/products/gcp/kubernetes-best-practices-resource-requests-and-limits) on resource request best practice. 
 
### Making sure a process stays healthy  
Kubernetes has a _control plane_ that automatically ensures the desired container topology is maintained - e.g. if a container crashes it will spin it up again. Lets put that to the test..  

Show running service pod:
```cmd
$ kubectl get pod --selector=app=service
```      
..should show something like:
```
NAME                            READY   STATUS    RESTARTS   AGE
mood-service-66dcb68c87-swbbm   1/1     Running   0          101m
```
Note RESTARTS is 0, we brought it up earlier and its continued running happily since.

Lets now simulate the process crashing. Under the hood kubernetes spins up containers using Docker, so lets go back to using the Docker CLI to stop the running container:
```cmd
$ export MOOD_SERVICE_CONTAINER_ID=$(docker ps -q -f name=mood-service)
$ docker stop $MOOD_SERVICE_CONTAINER_ID 
```
Right now the service is down, if someone tried it, it would fail, lets give Kubernetes a chance to detect the outage and spin up a new container...then check on the pod again:
```cmd
$ kubectl get pod --selector=app=service
```   
..this time its RESTART count has gone up:
```
NAME                            READY   STATUS    RESTARTS   AGE
mood-service-66dcb68c87-swbbm   1/1     Running   1          106m
```
(`docker ps` will show the new container as well)

### Using health checks 
We can help Kubernetes out a bit here by providing application health checks using [liveness and readiness probes](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/). Lets just focus on the liveness probe which tells Kubernetes a pod is running as expected - if a liveness probe starts to fail Kubernetes will automatically restart the pod.  

The service is configured to expose an HTTP probe using [Spring Boot Actuator](https://www.baeldung.com/spring-boot-actuators), defined through this dependency:
```cmd
<dependency>
 <groupId>org.springframework.boot</groupId>
 <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
``` 
Actuator is clever enough to figure out we need redis and runs a health check to ensure we can connect. The health probe can be accessed via:
```cmd
$ curl http://localhost:30001/actuator/health
{"status":"UP"}
```
This bit of deployment config defines an HTTP request based liveness check, it informs Kubernetes to wait 30 seconds (`initialDelaySeconds`) before performing the first probe, and then perform the probe every 30 seconds (`periodSeconds`); if the probe fails 3 times (default `failureThreshold`) the container is restarted. 
  
```cmd
livenessProbe:
  httpGet:
    path: /actuator/health
    port: 8080
  initialDelaySeconds: 15
  periodSeconds: 30
```
Get `initialDelaySeconds` wrong and the container may never start, forever been killed and restarted before it gets a chance to start! As such, it should be set to be greater than the maximum initialisation time. There's also a probe `timeoutSeconds` which defaults to 1 second, its worth considering a small increase in response time due to temporary increase in load could result in the container being restarted.  

A pod's [`restartPolicy`](https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/#restart-policy) determines what to do if a probe fails - if set to `Always` (the default) or `OnFailure`, the container will be killed and restarted if the probe fails (the other policy is `Never`)

The deployment does not include a probe yet, so lets use [`kubectl patch`](https://kubernetes.io/docs/tasks/run-application/update-api-object-kubectl-patch/) to apply one to the mood-service:
```cmd
$ kubectl patch deployment mood-service --patch "$(cat health-check-patch.yaml)"
```
(see [health-check-patch.yaml](docker/health-check-patch.yaml))

After applying the patch the pod will be restarted, check the patched deployment, which should now include the above probe config:
```cmd
kubectl get deployment mood-service --output yaml
``` 
(in practice you'd now get those changes back into version controlled deployment YAML) 

Lets check things are running as expected
```cmd
$ export SERVICE_POD=$(kubectl get pods --selector=app=service --no-headers -o custom-columns=":metadata.name")
$ kubectl describe pod ${SERVICE_POD} 
```
Look for the `Events` section, if the probe failed you'll see something like below (otherwise you wont see any probe related events):  
```cmd
  Type     Reason     Age                  From                     Message
  ----     ------     ----                 ----                     -------
  Warning  Unhealthy  10s (x3 over 1m48s)   kubelet, docker-desktop  Liveness probe failed: HTTP probe failed with statuscode: 500
```
..in this case probe has failed 3 times and the container has been restarted (`RESTARTS` is 1):
```cmd
kubectl get pods --selector=app=service
NAME                           READY   STATUS    RESTARTS   AGE
mood-service-d54f94b99-wvqn4   1/1     Running   1          3m35s
```  

## Concurrency  
[Scale out via the process model](https://12factor.net/concurrency) (12 factor)

Adding more concurrency is a case of spinning up a new process - horizontal scaling.

Different processes can be assigned a type - HTTP requests may be handled by a web process, and long-running background tasks handled by a worker process, and different types of process can be scaled differently - e.g. you may have three load balanced web processes and ten worker processes.

Back to our app, we want to be able to scale it out, but how? Containers and container orchestration is the answer (again).

zzz
The Kubernetes control plane has the ability to scale applications based on their resource utilisation - as pods become busier, it can automatically bring up new replicas (a clone of a pod) to share the load.

We already made a reference to the pod replica count 
`replicas`
```cmd
apiVersion: apps/v1
kind: Deployment
metadata:
  name: mood-redis
spec:
  replicas: 1
``` 
zzz 


Right now we just have one mood-service pod running (`replicas: 1`):  
```cmd
$ kubectl get deployment mood-service
NAME           READY   UP-TO-DATE   AVAILABLE   AGE
mood-service   1/1     1            1           24h

```
There is 1 READY & AVAILABLE pod.

This can be manually increased:
```cmd

```

There are a lot of good reasons for having a replica count > 1:
* Load balancing - Kubernetes can distribute the load between the replicas (this is what the Service object is partly about)
* High Availability - by adding a bit of redundancy, application availability has increased - e.g. if one of replica's stops responding, the others can continue to service requests  
* Ease of Update - allows [rolling updates](https://kubernetes.io/docs/tutorials/kubernetes-basics/update/update-intro/) and zero downtime

### Horizontal Autoscaling 
Kubernetes [Horizontal Pod Autoscaler](https://kubernetes.io/docs/tasks/run-application/horizontal-pod-autoscale/)

```cmd
$ kubectl autoscale deployment mood-service --cpu-percent=50 --min=1 --max=5
``` 

```cmd
$ kubectl run -i --tty load-generator --image=busybox /bin/sh  
Hit enter for command prompt  
$ while true; do wget -q -O- http://localhost:30001/user/anon/mood; done
```

 
Check out this nice [autoscaling blog](https://kubernetes.io/blog/2016/07/autoscaling-in-kubernetes/) for a bit more info.
 
## Config and Kubernetes
You may not have noticed but the Kubernetes instance of the service had a different default message:
```cmd 
$ curl http://localhost:30001/user/anon/mood
{... "status":404, "message":"default for Kubernetes"}
```
It's been set in the Kubernetes config via a [container environment variable](https://kubernetes.io/docs/tasks/inject-data-application/define-environment-variable-container/): 
```cmd
containers:
  - name: mood-service
    image: mood-service:1.0.0
    env:
      - name: mood_not_found_message
        value: "default for Kubernetes"
```
This is fine, although the value is hard coded in the version controlled YAML and can't be changed at runtime.

A Kubernetes [ConfigMap](https://kubernetes.io/docs/tasks/configure-pod-container/configure-pod-configmap/) provides an alternative approach to application configuration that still maintains the separate of application code from configuration. The ConfigMap object is just a set of key-value pairs the can be consumed by a pod.

Lets create a ConfigMap to store our representative application config:
```cmd
$ kubectl create configmap mood-service-config --from-literal=mood_not_found_message="default for ConfigMaps"
```
This can be viewed in the web dashboard or via:
```cmd
$ kubectl describe configmap
```
Now get an alternative version of the deployment config, its on a different git branch:
```cmd
git TODO
```
The only difference is how the env variable is sourced into the container:
```cmd
env:
  - name: mood_not_found_message
    valueFrom:
      configMapKeyRef:
        name: mood-service-config
        key: mood_not_found_message
``` 
...and test:
```cmd 
$ curl http://localhost:30001/user/anon/mood
{... "status":404, "message":"default for ConfigMaps"}
```

ConfigMaps could be generated on the fly (e.g. using `kubectl`) as part of a release process or created from version controlled YAML, with different configs for different target environments, it really depends on the build and release process. To get the YAML we created above, just run: 
```cmd
kubectl get configmap mood-service-config -o yaml
``` 
..although this has a bunch of stuff you don't want to version control (like creationTimestamp), the YAML is easy enough to hand craft though:

```cmd
apiVersion: v1
data:
  mood_not_found_message: default for ConfigMaps
kind: ConfigMap
metadata:
  name: mood-service-config
  namespace: default
```  
..and create using `kubectl create -f <config.yaml>`

# Deploying to Red Hat OpenShift
Sign up for free [here](https://www.openshift.com/products/online/) to get:
- 2GiB memory for your applications
- 2GiB persistent storage for your applications
- 60-day duration

Log in to admin home page 

![Admin](/images/admin.png)

Click 'Create Project' and enter details:

![Project](/images/project.png)

Now switch to 'Developer' mode to create the application topology:

![Developer](/images/developer.png)

Select 'Topology' and click 'add other content', you'll be presented with:

![Content](/images/content.png)

Create the redis service from the 'redis:6.0.3-alpine' container image used earlier - select 'Container Image' and enter details:

![Redis](/images/redis.png)

Things of note:
* Change 'Application Name' to 'mood-app'
* Change 'Name' to 'mood-redis', this will be used for the service name, and is part of the backing service name the mood service will be configured to use
* Uncheck 'Create a route to the application' as we don't want an external route to redis, just container container communication via service name

Hit 'Create' and once created click on 'Resources' tab, you'll see one pod and a service exposed on port 6379: 

![Redis](/images/redis_resources.png)

Now create the mood service from source code in GitHub, select 'Add' (from the right hand toolbar) and then choose 'From Git':

![Git](/images/from_git.png)
 
Enter following details:

![Mood Service](/images/mood.png)

Things of note:
* Once the `https://github.com/stehrn/twelve_factor.git` git repo is entered the UI will automatically select the correct builder image (Java) 
* 'Application' 'mood-app' is automatically selected, stick with it
* Change 'Name' to 'mood-service'
* We do want a Route creating as want a public URL we can connect to, so stick  with the default checked option

At the bottom of the form, click on advanced options for 'Build Configuration' and add env variable for redis backing service:

![Mood Service](/images/mood_advanced.png)

Hit 'Create' and once created click on 'Resources' tab, you'll see one pod and a service that exposes several ports, including 8080, there is also a publicly accessible route: 

![Mood Service](/images/mood_resources.png)

There's a nice 'topology view' you can toggle to by clicking icon in top right hand side (![toggle](/images/toggle.png)):

![Topology](/images/topology.png)

So lets test the application, grab the public route (you can click on the ![Route](/images/route.png) icon to obtain) and test:
```cmd
$ export ROUTE=http://mood-service-mood-board.apps.us-east-1.starter.openshift-online.com 
$ curl -X PUT -H "Content-Type: text/plain" -d "happy with Openshift" ${ROUTE}/user/stehrn/mood 
$ curl ${ROUTE}/user/stehrn/mood
{"user":"stehrn","mood":"happy with Openshift"}
```
Now is the time to spend some time in the UI clicking about to see what features are available and reading the (very good) [OpenShift documentation](https://docs.openshift.com/online/).

# Wrap up
A lot has been covered. 

Not all of the 12 factors we're touched on.
 
## (Factor 5) Build, release, run - strictly separate stages
 
 
xxx



## References
* https://www.ionos.com/community/hosting/redis/using-redis-in-docker-containers/
* https://docs.spring.io/spring-data/data-redis/docs/current/reference/html/#reference
* https://www.contino.io/insights/building-a-twelve-factor-application-part-one






XXX

So we have a highly available service but only one instance of redis - and if it goes down, the service is unusable. The [kubernetes article](https://kubernetes.io/docs/tutorials/stateless-application/guestbook/) explains how to add a redis master with a configurable number of slaves to provide high availability, and it's done through a bit of YAML. 



Good link
https://learnk8s.io/spring-boot-kubernetes-guide



And tidy up by tearing down the application:
```cmd
$ kubectl delete -f mood-app.yaml
```  

https://helm.sh


 then on full-blown public cloud ([Azure](https://azure.microsoft.com/en-gb/)).
 
 
 
Graalvm 
https://blog.softwaremill.com/small-fast-docker-images-using-graalvms-native-image-99c0bc92e70b
 

Openshift
https://console-openshift-console.apps.us-east-1.starter.openshift-online.com/k8s/cluster/projects

TODO
mvn clean package
DO tests work? 




