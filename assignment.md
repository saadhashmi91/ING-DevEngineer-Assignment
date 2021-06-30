# Technical Assignment

# Hashing-as-a-Service

At ING, we would like to build an simple [CLI](https://en.wikipedia.org/wiki/Command-line_interface) application that computes the hash of the content of a text file line-by-line and writes it to an output file (provided from command line). For instance:

```
$ compute-hash ./input.txt ./output.txt
```

Given `input.txt` as follows:

```
a
b
…
```

The `output.txt` should be :

```
hash-of-a
hash-of-b
…
```

Note following:

- The order of list of hash in `output.txt` should corresponds to the same order as in `input.txt`.
- The application should be able to process large input files without getting any out-of-memory exceptions.

# Approach

Instead of building the application from scratch, we have decided to reuse an existing REST service, which:

1. Accepts batch requests with a list of strings to hash.
2. Returns a list of hashes of that strings (in the same order).

*For instance:*

Submitting a job for processing: `POST /api/service`

```
{
    "id": "job-1",
    "lines": [
       "This a line",
       "And that's another line."
    ]
  }
```

You get following response back.

```
 {
    "id": "job-1",
    "lines": [
        "62812ce276aa9819a2e272f94124d5a1",
        "13ea8b769685089ba2bed4a665a61fde"
    ]
 }
```

Note that, due to implementation details and underlying network, there could be latency in responding to a request and also, that it can fail.

Example of error response: `HTTP 503 (Service Unavailable)`.

```
{
    "error": "Something went wrong!"
}
```

Detail of the service and how to run it is available [https://github.com/mlplatform/hashing-as-a-service](https://github.com/mlplatform/hashing-as-a-service).

In this assignment we will reuse this service and build this CLI application.

# Objectives + Technical Constraints 

0. Objective: _To derive a low latency and high throughput soluation_ to the problem with Actor-based concurrency and Functional Programming. 
1. Key Focuses: **out-of-order event handling of responses**, error handling, non-blocking solution to the problem.   
2. Please solve this problem using [Scala](https://www.scala-lang.org/) and Akka [Actors](https://doc.akka.io/docs/akka/current/actors.html).  
3. Note: You are **not allowed** to use any stream processing library (e.g., FS2, Akka Stream etc) as that would make the problem quite simple to solve due to the primitive provided.
4. You are allowed to use any libraries. However, please prefer to minimise the dependencies as much as possible.
5. Please consider a non-seekable output file stream (e.g., please do not use `RandomAccessFile`).  

## Deliverables

We expect to receive the solution in **this** private GIT repository. *By participating in this assignment, you are signing a non-disclosure agreement (NDA) that the solution of this assignment will **not** be available anywhere public than this GIT repository*.  

We also expect to receive the solution: 
- [ ]  With sufficient unit- and integration-tests,
- [ ]  With documentation, outlining your analyses and the design choices you made.
- [ ]  Bonus point: Functional effect handling.

If you have any question and/or do not agree with the NDA, please get in touch! 
