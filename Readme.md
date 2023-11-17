# Clofidence

Bolster your Clojure test suite confidence.

![screenshot](./images/screenshot.png)

Clofidence will instrument your codebase using [ClojureStorm](https://github.com/flow-storm/clojure), 
then run your tests and generate a test coverage report.

[![Clojars Project](https://img.shields.io/clojars/v/com.github.flow-storm/clofidence.svg)](https://clojars.org/com.github.flow-storm/clofidence)

## Quick start

### With the Clojure Cli

Add an alias to your `deps.edn` like this :

```clojure
{....
 :aliases 
 {...
  :clofidence {:classpath-overrides {org.clojure/clojure nil}
               :extra-deps {com.github.flow-storm/clojure {:mvn/version "LATEST"} ; >= 1.11.1-15
                            com.github.flow-storm/clofidence {:mvn/version "LATEST"}}
               :exec-fn clofidence.main/run
               :exec-args {:report-name "MyApp"
                           :test-fn cognitect.test-runner.api/test
                           :test-fn-args [{}]}
               :jvm-opts ["-Dclojure.storm.instrumentOnlyPrefixes=my-app"
                          "-Dclojure.storm.instrumentSkipPrefixes=my-app.unwanted-ns1,my-app.unwanted-ns2"
                          "-Dclojure.storm.instrumentSkipRegex=.*test.*"
                          ]}}}
```

Please make sure you have the latest versions of ClojureStorm and Clofidence.

With __Clofidence__ configured, every time you want to generate a coverage report you run :

```bash
$ clj -X:test:clofidence
```

After running the tests, it should generate a file called `MyApp-coverage.html`

## Configuration

The example above assumes your aliases contain a `:test` alias that will put the tests paths and test-runner 
on the classpath, but this will depend on your particular test setup.

There is a lot going on in the configuration, so let's walk over it :

  * First, we need to disable the official Clojure compiler, since we are going to replace it with ClojureStorm
  * Next, we add the latest ClojureStorm and Clofidence dependencies
  * `:exec-fn` and `:exec-args` tells the Clojure cli what  Clofidence main entry point is and with what arguments it should call it
    * `:report-name` just configures the report header and file name
    * `:test-fn` tells Clofidence what function will run your tests
    * `:test-fn-args` are the arguments to the function defined in `:test-fn`
  * Finally we need to tell Clofidence which namespaces to include and which to skip for the coverage
    * `instrumentOnlyPrefixes` should be a comma separated list of namespaces prefixes to include. Adding `my-app` will include everything 
    under `my-app.core` and `my-app.web.routes`.
    * `instrumentSkipPrefixes` can be used in the same way, but to skip unwanted namespaces.
    * `instrumentSkipRegex` should be a regex to match namespaces to skip
    
## Reports

[Here](/examples/ClojureScript-coverage.html) you can download and see the **Clofidence** report for the ClojureScript compiler v1.11.60.

As you can see, the report contains 3 sections, so let's go over them in more detail.

### Header counters

The **Total forms hit rate** shows how many top level forms were at least touched once by the tests, out of all the instrumented forms.

The **Total sub forms hit rate** shows how many sub-expressions were hit out of all instrumented ones.

### Header overview

The header overview shows one bar per namespace, and how much of it has been covered.

The size of the bars is proportional to the amount of code in the namespaces.

### Form details
  
Right after the overview, the report includes a list of all your instrumented forms, grouped by namespaces and sorted by coverage.

Forms will have a green background if the coverage is > 50%, a yellow background between 1% and 50% and a red background for forms with 0% coverage.

Each forms contains details of what sub-expressions were hit at least once. This is useful to identify parts of your forms that aren't being exercised
by the tests.

Hitted sub-expressions will be highlighted in green while hittable but not hit ones will be highlighted in red.
Skimming over the red ones should give you an overview of uncovered conditional branches, uncovered functions signatures, etc.


#### Which forms are included in the report?

By default, only forms with the first symbol name being one of : `defn`, `defn-`, `defmethod`, `extend-type`, `extend-protocol`, `deftype`, `defrecord` 
and `def` forms which define functions will be included.

If you have other types of forms (like the ones defined by some macros), you can include them by using `:extra-forms` in the configuration 
parameters. It takes a set of symbols like `:extra-forms #{defroutes}`.

If instead of using the default and allowing some extra forms you prefer the other way around, you can use the `:block-forms` config.
If you config it like `:block-forms #{}`, all forms will be included (with the exception of a few, see next) and you can block some
by adding them to the set, like `:block-forms #{my-annoying-form}`.

Even with `:block-forms #{}` there are some forms which are always excluded, which are `ns`, `defprotocol`, `quote`, `comment`, `def` with constants
which doesn't make sense.
