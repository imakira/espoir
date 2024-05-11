# Espoir - A Command Line French to English Dictionary

> espoir means "hope" in French.  

![img](./screenshots/espoir.png)  


## Installing


### Manuall Install

1.  Install [babashka](https://github.com/babashka/babashka)
2.  Save [espoir](https://raw.githubusercontent.com/imakira/espoir/main/espoir) somewhere and run it!  
    
    ```bash
    curl https://raw.githubusercontent.com/imakira/espoir/main/espoir > espoir
    chmod +x espoir
    ./espoir espoir
    ```


### Using Nix

```bash
nix run github:imakira/espoir -- espoir
```


## Usage

```bash
Usage: espoir [options] words
Options: 
  [option]              [default]  [descriptions]
  -s, --short           false      Show results in a more concise format, omitting some information.
  -a, --all             false      Show all translation sections (only principal translations are shown by default)
  -N, --no-inflections  false      Don't show inflection sections
  -n, --no-color        false      Disable ascii color output, env NO_COLOR is also supported
  -h, --help
```


## Screenshots


### Inflections Section

![img](screenshots/inflections.png)  


### Concise Format

![img](screenshots/concise.png)
