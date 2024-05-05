# Espoir - A Command Line French to English Dictionary

> espoir means "hope" in French.  

![img](./screenshots/espoir.png)  


## Installing

1.  Install [babashka](https://github.com/babashka/babashka)
2.  Save [espoir](https://raw.githubusercontent.com/imakira/espoir/main/espoir) somewhere and run it!  
    
    ```bash
    curl https://raw.githubusercontent.com/imakira/espoir/main/espoir > espoir
    chmod +x espoir
    ./espoir espoir
    ```


## Usage

> Usage: espoir [options] words  
> Options:  
>   [option]              [default]  [descriptions]  
>   -s, &#x2013;short           false      Show results in a more concise format, omitting some information.  
>   -a, &#x2013;all             false      Show all translation sections (only principal translations are shown by default)  
>   -N, &#x2013;no-inflections  false      Don't show inflection sections  
>   -h, &#x2013;help  


## Screenshots


### Inflections Section

![img](screenshots/inflections.png)  


### Concise Format

![img](screenshots/concise.png)
