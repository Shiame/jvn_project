# Projet Javanaise - JVN1

Ce projet implemente un cache d'objets Java repartis au-dessus de Java RMI.
La version realisee ici est JVN1 : l'utilisation est non transparente pour le
programmeur. L'application utilise explicitement `JvnObject`, les verrous et
`jvnGetObjectState()`.

## Organisation

```text
src/irc/Irc.java
    Application cliente de test avec une petite interface de chat.

src/irc/Sentence.java
    Objet metier partage. Il contient le texte du chat.

src/jvn/JvnObject.java
    Interface de l'objet d'interception.

src/jvn/JvnObjectImpl.java
    Implementation de l'objet d'interception et gestion des verrous locaux.

src/jvn/JvnServerImpl.java
    Serveur local de chaque JVM cliente.

src/jvn/JvnCoordImpl.java
    Coordinateur central : ids, objets, lecteurs et ecrivain.

src/jvn/JvnCoordLauncher.java
    Demarre le registre RMI et publie le coordinateur.
```

Architecture :

```text
Irc
 |
 v
JvnObjectImpl
 |
 v
JvnServerImpl  <---- RMI ---->  JvnCoordImpl
                                      |
                                      v
                           autres JvnServerImpl
```

## Prerequis

Verifier que Java est installe :

```bash
java -version
javac -version
```

Les commandes suivantes doivent etre executees depuis le dossier `SOURCES` :

```bash
cd "/home/chaymae/Documents/ChatGPT/projet javanaise/DONNE-JVN-26-27/DONNE-JVN-26-27/SOURCES"
```

## Compilation propre

Utiliser un seul dossier de classes pour le coordinateur et tous les clients.
Cela evite les erreurs de versions RMI et de `serialVersionUID`.

```bash
rm -rf /tmp/jvn
mkdir -p /tmp/jvn
javac -d /tmp/jvn src/jvn/*.java src/irc/*.java
```

Si la commande termine sans message d'erreur, la compilation est correcte.

## Demarrer le coordinateur

Le coordinateur correspond a JVM3. Il doit etre demarre avant les clients.

Dans un premier terminal :

```bash
cd "/home/chaymae/Documents/ChatGPT/projet javanaise/DONNE-JVN-26-27/DONNE-JVN-26-27/SOURCES"
java -cp /tmp/jvn jvn.JvnCoordLauncher
```

Message attendu :

```text
JVN Coordinator ready
```

Le coordinateur utilise le registre RMI sur le port `1099` et est publie sous
le nom `JvnCoord`.

## Demarrer les clients IRC

Dans un deuxieme terminal :

```bash
cd "/home/chaymae/Documents/ChatGPT/projet javanaise/DONNE-JVN-26-27/DONNE-JVN-26-27/SOURCES"
java -cp /tmp/jvn irc.Irc
```

Dans un troisieme terminal, lancer la meme commande :

```bash
java -cp /tmp/jvn irc.Irc
```

Les deux fenetres representent deux applications clientes dans deux JVM
distinctes. Elles doivent partager l'objet logique nomme `IRC`.

## Test lecture/ecriture

1. Demarrer le coordinateur.
2. Demarrer deux clients `irc.Irc`.
3. Dans le client A, ecrire `bonjour` dans le champ texte.
4. Cliquer sur `write`.
5. Dans le client B, cliquer sur `read`.
6. Le client B doit afficher `bonjour`.
7. Ecrire ensuite une autre valeur depuis B.
8. Cliquer sur `read` depuis A.

Le texte ne s'affiche pas automatiquement dans les autres fenetres. Un client
doit cliquer sur `read` pour demander la version actuelle de l'objet.

## Sequence principale

### Creation du premier objet

```text
Irc
 -> JvnServerImpl.jvnGetServer()
 -> jvnLookupObject("IRC")
 -> objet absent
 -> jvnCreateObject(new Sentence())
 -> JvnCoordImpl.jvnGetObjectId()
 -> creation de JvnObjectImpl en etat W
 -> jvnUnLock() : W -> WC
 -> jvnRegisterObject("IRC", objet)
```

### Lecture par un autre client

```text
Client B
 -> jvnLookupObject("IRC")
 -> reception d'un JvnObject en etat NL
 -> jvnLockRead()
 -> JvnServerImpl.jvnLockRead()
 -> JvnCoordImpl.jvnLockRead()
 -> invalidation ou conversion de l'ancien ecrivain
 -> retour de la derniere version
 -> lecture de Sentence
 -> jvnUnLock() : R -> RC
```

### Ecriture

```text
Client A
 -> jvnLockWrite()
 -> invalidation des autres lecteurs
 -> etat W
 -> modification de Sentence
 -> jvnUnLock() : W -> WC
```

## Etats des verrous

```text
NL   aucun verrou local
RC   verrou de lecture en cache, non utilise
WC   verrou d'ecriture en cache, non utilise
R    lecture en cours
W    ecriture en cours
RWC  verrou d'ecriture en cache utilise pour lire
```

Transitions importantes :

```text
NL  --lockRead-->  R
RC  --lockRead-->  R
WC  --lockRead-->  RWC
NL  --lockWrite->  demande distante, puis W
RC  --lockWrite->  W
WC  --lockWrite->  W
R   --unlock--->   RC
W   --unlock--->   WC
RWC --unlock--->   WC
```

Pour `NL` et `RC`, une demande d'ecriture peut devoir contacter le
coordinateur. Le passage a `W` est confirme seulement apres la reponse du
coordinateur. Pour `WC` et `RWC`, le write lock est deja en cache et peut etre
repris localement.

## Stress test JVN1

Le stress test lance plusieurs clients en parallele. Chaque client effectue
alternativement une lecture et une ecriture sur l'objet partage `IRC`.
Avec `1000` iterations, un client doit obtenir `500` lectures et `500`
ecritures.

### Compilation avec le burst test

Depuis le dossier `SOURCES` :

```bash
rm -rf /tmp/jvn
mkdir -p /tmp/jvn
javac -d /tmp/jvn src/jvn/*.java src/irc/*.java src/burst/*.java
```

### Lancement

Dans un premier terminal, lancer le coordinateur :

```bash
java -cp /tmp/jvn jvn.JvnCoordLauncher
```

Dans un deuxieme terminal, lancer une fenetre IRC afin de creer et
d'enregistrer l'objet partage :

```bash
java -cp /tmp/jvn irc.Irc
```

Quand la fenetre IRC est ouverte, lancer le test dans un autre terminal :

```bash
for i in 1 2 3 4 5
do
    java -cp /tmp/jvn burst.BurstClient "$i" 1000 &
done

wait
```

Resultat attendu pour chaque client :

```text
Client 1 finished: reads=500, writes=500, errors=0, duration=...
```

L'ordre des clients et la duree peuvent changer. Le resultat important est
`reads=500`, `writes=500` et surtout `errors=0` pour les cinq clients.

Il est inutile de relancer le coordinateur entre les clients. En revanche,
apres une recompilation, il faut redemarrer le coordinateur et les clients,
car les anciennes classes peuvent rester chargees en memoire.

## Erreurs rencontrees et corrections

### Objet distant non serialisable correctement

Erreur :

```text
cannot assign instance of jdk.proxy... to field
jvn.JvnObjectImpl.server of type jvn.JvnLocalServer
```

`JvnObjectImpl` est transmis par RMI, mais sa reference vers le serveur local
ne doit pas etre serialisee. La reference est donc `transient` et elle est
reinstallee par `JvnServerImpl` apres le lookup.

```java
private transient JvnLocalServer server;
```

### Incompatibilite `serialVersionUID`

Erreur :

```text
InvalidClassException: jvn.JvnObjectImpl;
local class incompatible
```

Toutes les JVM doivent utiliser la meme version de la classe serialisable.
On a ajoute un identifiant explicite :

```java
private static final long serialVersionUID = 1L;
```

Il faut ensuite recompiler toutes les classes dans le meme dossier `/tmp/jvn`
et redemarrer le coordinateur.

### Deadlock pendant les invalidations

Au debut, `JvnCoordImpl` gardait son verrou `synchronized` pendant les appels
distants comme `jvnInvalidateReader()` et `jvnInvalidateWriter()`.
Le coordinateur attendait alors le client, tandis que le client attendait le
coordinateur pour terminer son propre appel de verrouillage.

Correction : le coordinateur reserve une transition, libere son verrou Java,
effectue le callback RMI, puis reprend son verrou pour mettre a jour l'etat.
Le champ suivant empeche deux transitions concurrentes sur le meme objet :

```java
boolean transitionInProgress;
```

### Etats incoherents sous forte concurrence

Le burst test produisait notamment :

```text
No write lock to invalidate
No write lock to reduce to reader
Cannot invalidate writer in state 1
Cannot invalidate reader in state 4
```

Ces erreurs apparaissaient lorsque plusieurs clients demandaient des verrous
en meme temps, notamment quand deux clients en `RC` demandaient un write lock.

Corrections dans `JvnObjectImpl` :

1. Une demande `lockRead()` depuis `NL` reserve l'etat `R` pendant l'appel
   distant et revient a `NL` si l'appel echoue.
2. Une demande `lockWrite()` depuis `NL` ou `RC` devient `W` seulement apres
   l'accord du coordinateur. Cela permet a une invalidation concurrente de
   traiter encore l'ancien etat `RC`.
3. Les invalidations attendent avec `wait()` lorsque le verrou est utilise
   (`R`, `W` ou `RWC`) et sont reveillees par `notifyAll()` dans `jvnUnLock()`.
4. Une invalidation deja effectuee est toleree. Par exemple, si l'etat est
   deja `NL` ou `RC`, un callback repete ne fait pas echouer toute la
   transition.

Corrections dans `JvnCoordImpl` :

1. Le coordinateur conserve pour chaque objet son identifiant, son dernier
   etat valide, son ecrivain et l'ensemble de ses lecteurs.
2. `jvnLockRead()` transforme l'ancien ecrivain en lecteur, puis ajoute le
   nouveau lecteur.
3. `jvnLockWrite()` invalide l'ancien ecrivain et tous les autres lecteurs,
   puis donne le write lock au demandeur.
4. Les callbacks distants ne sont jamais executes pendant que le moniteur du
   coordinateur est conserve.

## Interpretation du resultat

Avec cinq clients et `1000` operations chacun, le test execute :

```text
2500 lectures au total
2500 ecritures au total
0 erreur attendue
```

La duree depend de la machine et de Java RMI. Un resultat correct ressemble
donc a :

```text
Client 1 finished: reads=500, writes=500, errors=0, duration=...
...
Client 5 finished: reads=500, writes=500, errors=0, duration=...
```

## Arret et nettoyage

Pour fermer un client, fermer sa fenetre ou interrompre son terminal.

Pour arreter le coordinateur, aller dans son terminal et faire :

```text
Ctrl+C
```

Si le port `1099` est deja utilise, un ancien coordinateur fonctionne encore.
Arreter cet ancien processus avant de relancer.

Ne pas demarrer deux coordinateurs en meme temps sur le port `1099`.

## Problemes courants

### Port 1099 deja utilise

Un coordinateur est deja actif. Utiliser `Ctrl+C` dans son terminal, puis
relancer une seule instance.

### `ClassNotFoundException`

Verifier que le client est lance avec le meme dossier de classes :

```bash
java -cp /tmp/jvn irc.Irc
```

### `InvalidClassException` ou `serialVersionUID`

Recompiler toutes les classes dans un dossier vide, puis redemarrer le
coordinateur et tous les clients :

```bash
rm -rf /tmp/jvn
mkdir -p /tmp/jvn
javac -d /tmp/jvn src/jvn/*.java src/irc/*.java
```

`JvnObjectImpl` doit contenir un identifiant explicite :

```java
private static final long serialVersionUID = 1L;
```

### Les clients ne voient pas la derniere valeur

Verifier que :

1. `info.writer = js` est present dans `jvnRegisterObject()` ;
2. `jvnLookupObject()` renvoie un nouvel objet en etat `NL` ;
3. le coordinateur a ete redemarre apres chaque recompilation ;
4. le bouton `read` a ete clique dans l'autre fenetre.

## Limites de cette version

Cette implementation correspond a JVN1. Le programmeur doit ecrire
explicitement :

```java
jvnObject.jvnLockRead();
Sentence s = (Sentence) jvnObject.jvnGetObjectState();
s.read();
jvnObject.jvnUnLock();
```

La transparence et la generation d'objets d'interception specifiques sont des
objectifs de JVN2, pas de cette premiere version.
