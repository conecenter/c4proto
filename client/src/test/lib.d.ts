
interface Boolean {}
interface CallableFunction {}
interface Function {}
interface IArguments {}
interface NewableFunction {}
interface Number {}
interface Object {}

interface String {
    replaceAll(searchValue: string | RegExp, replaceValue: string): string;
    startsWith(searchString: string, position?: number): boolean
    substring(start: number, end?: number): string
    split(by: string): string[]
}
declare function parseInt(string: string, radix?: number): number

declare var Symbol: {
    readonly iterator: unique symbol
}

interface Iterator<T,U=any,V=any> {}
interface Iterable<T,U=any,V=any> {}

declare var Object: {
    entries<T>(o: { [s: string]: T }): [string, T][]
    keys(o: object): string[]
    groupBy<K extends string, T>(items: Iterable<T>, keySelector: (item: T, index: number) => K): { [s: string]: T[] }
    fromEntries<T>(entries: Iterable<[string, T]>): { [k: string]: T }
}

interface ArrayIterator<T> {}
interface Array<T> {
    [n: number]: T
    [Symbol.iterator](): ArrayIterator<T>
    length: number
    every<S extends T>(predicate: (value: T, index: number, array: T[]) => value is S): this is S[]
    every(predicate: (value: T, index: number, array: T[]) => boolean): boolean
    filter<S extends T>(predicate: (value: T, index: number, array: T[]) => value is S): S[]
    filter(predicate: (value: T, index: number, array: T[]) => boolean): T[]
    flatMap<U>(callback: (value: T, index: number, array: T[]) => U | U[]): U[]
    forEach(callbackfn: (value: T, index: number, array: T[]) => void): void
    indexOf(value: T): number
    join(separator: string): string
    map<U>(callbackfn: (value: T, index: number, array: T[]) => U): U[]
    reduce<U>(callbackfn: (previousValue: U, currentValue: T, currentIndex: number, array: T[]) => U, initialValue: U): U
    slice(start?: number, end?: number): T[]
    some(predicate: (value: T, index: number, array: T[]) => boolean): boolean
    toSorted(compareFn?: (a: T, b: T) => number): T[]
}
declare var Array: {
    isArray(arg: unknown): arg is unknown[]
}

interface WeakMap<K extends object, V> {
    get(key: K): V | undefined
    set(key: K, value: V): this
}
declare var WeakMap: {
    new <K extends object, V>(): WeakMap<K, V>
}

interface Promise<T> {
    then<TResult1 = T, TResult2 = never>(
        onfulfilled?: ((value: T) => TResult1 | Promise<TResult1>) | undefined | null, 
        onrejected?: ((reason: unknown) => TResult2 | Promise<TResult2>) | undefined | null
    ): Promise<TResult1 | TResult2>;
}

interface ErrorOptions {
    cause?: unknown
}
interface Error {}
declare var Error: {
    (message?: string, options?: ErrorOptions): Error
}

interface RegExp {}

declare var Date: {
    now(): number
}

declare var JSON: {
    parse(text: string): unknown
    stringify(value: unknown): string
}

///

declare var console: {
    trace(arg: unknown): void
}

interface Headers { [K: string]: string }
interface RequestInit {
    body?: string
    headers?: Headers
    method?: string    
}
interface Response extends Body {
    readonly headers: Headers
    readonly ok: boolean
    readonly status: number
}
interface Body {
    json(): Promise<unknown>
    text(): Promise<string>
}

interface Window extends EventTarget {
    setInterval(handler: ()=>void, timeout: number): number
    clearInterval(id: number): void
    cancelAnimationFrame(handle: number): void
    requestAnimationFrame(callback: ()=>void): number
    readonly sessionStorage: Storage
    get location(): Location
    document: Document
    WebSocket: { new(url: string): WebSocket }
    fetch(input: string, init?: RequestInit): Promise<Response>
}
interface Document extends Node {
    readonly defaultView: Window | null
    body: HTMLElement
    createElement(tagName: string): HTMLElement
}
interface Location {
    href: string
}

interface HashChangeEvent extends Event {
    readonly newURL: string
}
interface MessageEvent extends Event {
    readonly data: string
}
interface EventMap {
    "error": Event
    "message": MessageEvent
    "open": Event
    "hashchange": HashChangeEvent
    "beforeunload": Event
}
interface EventTarget {
    addEventListener<K extends keyof EventMap>(
        type: K, listener: (ev: EventMap[K]) => void, options?: boolean
    ): void
    removeEventListener<K extends keyof EventMap>(
        type: K, listener: (ev: EventMap[K]) => void
    ): void
}
interface WebSocket extends EventTarget {
    send(data: string): void
    close(): void
}

interface Storage {
    getItem(key: string): string | null
    removeItem(key: string): void
    setItem(key: string, value: string): void
}

interface Node extends EventTarget {
    appendChild<T extends Node>(node: T): T
    readonly parentElement: HTMLElement | null
    removeChild<T extends Node>(child: T): T
}
interface Element extends Node {
    id: string
    readonly ownerDocument: Document
}
interface HTMLElement extends Element {}
interface HTMLIFrameElement extends HTMLElement {
    readonly contentWindow: Window | null
}
interface HTMLInputElement {
    value: string
}
