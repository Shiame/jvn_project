# Projet Javanaise - JVN2 avec correction du burst test

Ce projet implemente un cache d'objets Java repartis au-dessus de Java RMI.
La version realisee ici est JVN2 : l'application utilise une interface metier,
des annotations et un proxy dynamique. Le proxy demande et libere les verrous
automatiquement.

## Organisation

```text
src/irc/Irc.java
    Application cliente de test avec une petite interface de chat.

src/irc/Sentence.java
    Objet metier partage. Il contient le texte du chat.

src/irc/SentenceItf.java
    Interface metier annotee avec les operations de lecture et d'ecriture.

src/jvn/JvnObject.java
    Interface de l'objet d'interception.

src/jvn/JvnObjectImpl.java
    Implementation de l'objet d'interception et gestion des verrous locaux.

src/jvn/JvnProxy.java
    Proxy dynamique qui intercepte read() et write() puis gere les verrous.

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
JvnProxy
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
cd "/home/chaymae/Documents/ChatGPT/projet javanaise/DONNE-JVN-26-27-JVN2/DONNE-JVN-26-27/SOURCES"
```

## Compilation propre

Utiliser un seul dossier de classes pour le coordinateur et tous les clients.
Cela evite les erreurs de versions RMI et de `serialVersionUID`.

```bash
rm -rf /tmp/jvn
mkdir -p /tmp/jvn
javac -d /tmp/jvn src/jvn/*.java src/irc/*.java src/burst/*.java
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

## Burst test JVN2

Le test `BurstProxyClient` utilise le proxy dynamique de JVN2. Il ne manipule
jamais `JvnObject` ni les verrous directement.

1. Compiler le projet avec la commande ci-dessus.
2. Demarrer le coordinateur :

```bash
java -cp /tmp/jvn jvn.JvnCoordLauncher
```

3. Dans un autre terminal, initialiser une seule fois l'objet partage :

```bash
java -cp /tmp/jvn burst.CrashWriterClient "initialisation"
```

Laisser ce terminal ouvert pendant le test. Ce client cree l'objet `IRC` et
reste disponible pour repondre aux invalidations RMI.

4. Dans un troisieme terminal, lancer cinq clients concurrents :

```bash
for i in 1 2 3 4 5
do
    java -cp /tmp/jvn burst.BurstProxyClient "$i" 1000 &
done
wait
```

Chaque client doit finir avec `reads=500`, `writes=500` et `errors=0`.
Le coordinateur doit etre demarre avant tous les clients.

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
NL  --lockWrite->  W
RC  --lockWrite->  W
WC  --lockWrite->  W
R   --unlock--->   RC
W   --unlock--->   WC
RWC --unlock--->   WC
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
