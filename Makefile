# Convenience wrapper around ./mvnw, matching the layout of the sibling
# projects. Everything here is a one-line shortcut - the build itself is Maven.

JAR      := target/weathermap-0.1.0-SNAPSHOT.jar
FAT_JAR  := target/weathermap-0.1.0-SNAPSHOT-jar-with-dependencies.jar

.PHONY: all jar netcdf test run cli clean

all: jar

## jar - build and test
jar:
	./mvnw -B package

## netcdf - build with the optional NetCDF-Java GRIB2 decoder
netcdf:
	./mvnw -B package -Pnetcdf

## test - run the tests only
test:
	./mvnw -B test

## run - open the desktop application
run: $(JAR)
	java -jar $(JAR)

## cli - repeat the last selection against the newest run
cli: $(JAR)
	java -jar $(JAR) --cli

clean:
	./mvnw -B clean
