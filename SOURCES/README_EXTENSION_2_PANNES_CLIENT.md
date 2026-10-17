# Extension 2 - Tolerance aux pannes client

Cette branche prolonge JVN2. Elle traite le cas ou une JVM cliente disparait
alors que le coordinateur la connait encore comme lecteur ou ecrivain.

## Problematique

Le coordinateur garde, pour chaque objet partage :

- le dernier etat connu de l'objet ;
- le client qui possede le droit d'ecriture (`writer`) ;
- les clients possedant un droit de lecture en cache (`readers`).

Pour donner un verrou incompatible a un autre client, il rappelle ces clients
par RMI : `jvnInvalidateReader`, `jvnInvalidateWriter` ou
`jvnInvalidateWriterForReader`.

Avant cette extension, si une JVM cible etait arretee, l'appel RMI echouait et
la demande de verrou du client sain echouait aussi. Le coordinateur conservait
en plus une reference vers un client mort.

## Choix realise : detection reactive de panne

Le coordinateur intercepte `RemoteException` autour de chaque rappel RMI dans
`JvnCoordImpl`.

Lorsqu'un rappel echoue, `removeFailedClient` :

1. retire le client de `readers` ;
2. retire aussi `writer` si ce client etait l'ecrivain ;
3. conserve `info.state`, le dernier etat deja connu par le coordinateur ;
4. laisse la demande de verrou en cours continuer.

Ainsi, un lecteur ou un ecrivain mort ne bloque plus durablement les autres
clients. La detection est dite reactive : on sait qu'un client est mort au
moment precis ou le coordinateur essaye de le contacter.

## Pourquoi ne pas mettre a jour le coordinateur a chaque unlock ?

Une autre solution serait d'envoyer l'etat de l'objet et de rendre les
verrous au coordinateur apres chaque `jvnUnLock()`.

Nous ne retenons pas cette solution, car elle annule l'interet principal de
Javanaise : le cache de verrous. Apres un `unlock`, un client peut normalement
garder un verrou `RC` ou `WC` et reutiliser l'objet localement sans nouvel
appel RMI. Si chaque unlock force un echange avec le coordinateur et retire le
verrou, chaque lecture/ecriture demande a nouveau le reseau. Le cache devient
alors presque inutile.

Le choix realise conserve donc les etats `RC`, `WC` et `RWC` de JVN2. Le cout
reseau supplementaire existe uniquement lorsqu'un verrou incompatible impose
deja une invalidation, ce qui est le moment normal de synchronisation.

## Limite assumee

Si un ecrivain tombe en panne apres avoir modifie son etat local mais avant de
l'avoir renvoye au coordinateur, sa derniere modification peut etre perdue.
Le coordinateur ne peut fournir que `info.state`, sa derniere copie connue.
Cette limite est normale pour cette extension ; la sauvegarde durable du
coordinateur releve de l'extension 3.

## Fichiers modifies ou ajoutes

- `src/jvn/JvnCoordImpl.java` : detection de `RemoteException` et nettoyage
  des references vers le client mort.
- `src/burst/CrashWriterClient.java` : ecrit une valeur puis attend, afin de
  pouvoir simuler une panne de JVM.
- `src/burst/ReadAfterFailureClient.java` : lit l'objet apres la panne.

## Compilation

Executer depuis `SOURCES` :

```bash
rm -rf /tmp/jvn-ext2
mkdir -p /tmp/jvn-ext2
javac -d /tmp/jvn-ext2 src/jvn/*.java src/irc/*.java src/burst/*.java
```

## Test manuel de panne

Dans un premier terminal, demarrer le coordinateur :

```bash
java -cp /tmp/jvn-ext2 jvn.JvnCoordLauncher
```

Dans un deuxieme terminal, demarrer un ecrivain :

```bash
java -cp /tmp/jvn-ext2 burst.CrashWriterClient "bonjour"
```

Lorsque le message `Writer ready` apparait, interrompre brutalement ce client
avec `Ctrl+C` (ou `kill -9 <pid>`). Il reste alors reference comme ecrivain
dans le coordinateur, mais il ne repond plus.

Dans un troisieme terminal, demander une lecture :

```bash
java -cp /tmp/jvn-ext2 burst.ReadAfterFailureClient
```

Le coordinateur tente de reduire le verrou de l'ancien ecrivain. L'appel RMI
echoue, le client est retire, puis la lecture se termine. Le coordinateur doit
afficher :

```text
JVN: client unreachable for object 0
```

La valeur lue est la derniere valeur connue du coordinateur. Elle peut etre
ancienne dans le cas de la limite expliquee plus haut.

## Branches a montrer

```text
jvn1                       JVN1, API explicite
jvn2                       JVN2, annotations et proxy dynamique
extension-2-pannes-client  JVN2 + tolerance aux pannes client
```
