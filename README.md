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
