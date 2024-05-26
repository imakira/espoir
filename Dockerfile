FROM babashka/babashka:latest
WORKDIR /app
COPY ./espoir .
# install babashka ponds
RUN ./espoir
ENTRYPOINT ["./espoir"]
