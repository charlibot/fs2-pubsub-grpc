# FS2 Pub/Sub GRPC client

Uses Typelevel's [fs2-grpc plugin](https://github.com/typelevel/fs2-grpc) to generate a Pub/Sub client directly from the Google proto files. The project includes adds a wrapper to make consuming streaming pulls easier to work with, inspired by Permutive's [fs2-google-pubsub library](https://github.com/permutive/fs2-google-pubsub).

## Building

To build the project:

```shell
sbt compile
```

## Running

Try running the example with:

```shell
sbt "project pubsub-example" "run projects/{project}/subscriptions/{sub}"
```

Note the application will require Google credentials to be available locally.
