import { DOMParser } from 'xmldom';
import process from "process";

export function DOMParserNoWarning(...args){
    return new DOMParser({
        locator: {},
        errorHandler: { warning: function (w) { }, 
        error: function (e) { }, 
        fatalError: function (e) { console.error(e) } }
    });
}

export function output(...args){
    process.stdout.write(args.join(" "));
}
