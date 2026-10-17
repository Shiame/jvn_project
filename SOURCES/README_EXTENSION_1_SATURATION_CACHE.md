# Extension 1 - Gestion de la saturation du cache client

Cette branche ajoute une limite au nombre d'objets Javanaise conserves dans la
memoire d'une JVM cliente. Quand la limite est atteinte, le serveur local peut
flusher des objets partages inutilises.

## Probleme traite

Dans JVN2, chaque `jvnLookupObject()` ajoute un objet a deux maps du serveur
local : `objects` et `namedObjects`. Sans limite, un client qui consulte un
grand nombre d'objets garde indefiniment toutes leurs references et leurs
verrous en cache. Sa memoire augmente donc continuellement.

## Politique choisie : LRU

`objects` est maintenant un `LinkedHashMap` avec `accessOrder=true`. Il garde
les objets dans l'ordre de leur dernier acces local. La capacite est lue au
demarrage de la JVM depuis :

```text
jvn.cache.max.objects
```

La valeur par defaut est `32`. Exemple pour limiter le cache a un objet :

```bash
java -Djvn.cache.max.objects=1 -cp /tmp/jvn-ext1 burst.CacheSaturationClient
```

Quand un ajout fait depasser la capacite, `JvnServerImpl` parcourt les objets
du moins recemment utilise au plus recemment utilise. Il evacue le premier
objet qui n'est pas utilise par l'application.

## Etats qui peuvent etre flushes

Un objet est flushable seulement lorsqu'il est dans un etat de cache :

```text
NL  aucun verrou local
RC  verrou de lecture en cache
WC  verrou d'ecriture en cache
```

Un objet dans `R`, `W` ou `RWC` est actuellement utilise par `read()` ou
`write()`. Il ne doit pas etre retire. Si tous les objets sont utilises, le
cache peut donc depasser temporairement sa capacite : c'est preferable a la
perte d'un verrou actif.

## Sequence de flush

Pour eviter une course entre un flush et une utilisation par le proxy,
`JvnObjectImpl` positionne d'abord `flushInProgress`.

```text
JvnServerImpl
 -> JvnObjectImpl.jvnBeginFlush()
 -> JvnCoordImpl.jvnFlushObject(id, state, client)
 -> JvnObjectImpl.jvnCompleteFlush()
 -> suppression de l'objet dans objects et namedObjects
```

Pendant `flushInProgress`, une nouvelle demande `read()` ou `write()` attend.
Si le flush echoue, `jvnAbortFlush()` annule ce marqueur et l'objet reste dans
le cache local.

`JvnProxy` marque egalement l'objet pendant toute l'execution de la methode
metier avec `proxyUses`. Un objet utilise par un proxy ne peut donc pas etre
evacue entre l'acquisition du proxy et son `unlock`. Si un ancien proxy pointe
vers un objet deja flushe, il effectue un lookup local et recupere un nouvel
objet avant l'appel suivant.

## Role du coordinateur

La methode distante `jvnFlushObject()` a ete ajoutee a `JvnRemoteCoord`.

- Si le client etait seulement lecteur, le coordinateur le retire de
  `readers`.
- Si le client etait ecrivain, il recupere l'etat transmis, l'enregistre dans
  `info.state`, puis retire `writer`.

Ainsi, l'objet peut etre oublie localement sans qu'un lecteur ou un ecrivain
fantome reste inscrit au coordinateur. Lors d'un prochain lookup, le client
recoit un nouveau `JvnObjectImpl` avec le dernier etat connu.

## Fichiers modifies ou ajoutes

- `src/jvn/JvnObjectImpl.java` : protection `flushInProgress`.
- `src/jvn/JvnServerImpl.java` : capacite, LRU, flush automatique et manuel.
- `src/jvn/JvnProxy.java` : reacquisition d'un objet evince avant un appel.
- `src/jvn/JvnCoordImpl.java` et `JvnRemoteCoord.java` : liberation d'un
  objet cote coordinateur.
- `src/burst/CacheSaturationClient.java` : demonstration executable.

## Compilation et test

Depuis `SOURCES` :

```bash
rm -rf /tmp/jvn-ext1
mkdir -p /tmp/jvn-ext1
javac -d /tmp/jvn-ext1 src/jvn/*.java src/irc/*.java src/burst/*.java
```

Dans un premier terminal :

```bash
java -cp /tmp/jvn-ext1 jvn.JvnCoordLauncher
```

Dans un second terminal :

```bash
java -Djvn.cache.max.objects=1 -cp /tmp/jvn-ext1 burst.CacheSaturationClient 3
```

Resultat attendu :

```text
Cache size after creation: 1
Flushed objects: 1
Cache size after manual flush: 0
Restored value: value-0
```

Le dernier lookup recharge `CACHE-0` depuis le coordinateur. Sa valeur montre
que l'eviction de l'ecrivain a bien transfere son etat avant de retirer le
cache local.
