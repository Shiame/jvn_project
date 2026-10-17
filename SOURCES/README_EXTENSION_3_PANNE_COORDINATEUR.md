# Extension 3 - Tolerance aux pannes du coordinateur

Cette branche prolonge `extension-2-pannes-client`. Elle traite le cas ou la
JVM du coordinateur s'arrete puis redemarre sur la meme machine. L'hypothese
du sujet est que le disque de cette machine n'est pas perdu.

## Probleme avant l'extension

Sans persistance, `JvnCoordImpl` conserve uniquement en memoire :

- `nextObjectId` ;
- les noms et identifiants des objets partages ;
- le dernier etat connu de chaque objet ;
- le lecteur ou l'ecrivain connu par le coordinateur.

Si la JVM du coordinateur tombe, toutes ces structures `HashMap` disparaissent.
Apres le redemarrage, le coordinateur repart a l'identifiant `0` et ne connait
plus les objets existants. Une demande de verrou sur un ancien identifiant
echoue avec `Unknown object id`.

## Choix realise : image persistante du coordinateur

`JvnCoordImpl` ecrit une image serialisee du coordinateur dans un fichier.
Par defaut, ce fichier est :

```text
/tmp/jvn-coordinator-state.bin
```

Le chemin peut etre choisi explicitement avec la propriete Java :

```text
-Djvn.coord.state.file=/chemin/vers/jvn-state.bin
```

L'image contient uniquement les donnees fiables apres un redemarrage :

- le prochain identifiant disponible ;
- le nom de chaque objet partage ;
- son identifiant ;
- son dernier etat serialisable connu du coordinateur.

Elle est sauvegardee apres l'allocation d'un identifiant, l'enregistrement
d'un objet et chaque retour d'etat provenant d'une invalidation d'ecrivain.
L'ecriture utilise un fichier temporaire suivi d'un deplacement atomique : un
crash pendant une sauvegarde laisse donc l'ancienne image intacte.

Au redemarrage, `JvnCoordImpl.create()` relit cette image et reconstruit les
objets avec un etat de verrou `NL`.

## Pourquoi `writer` et `readers` ne sont pas sauvegardes ?

`writer` et `readers` sont des references RMI vers des JVM clientes. Apres une
panne du coordinateur, elles peuvent etre mortes, redemarrees, ou ne plus
correspondre a la meme session RMI. Les restaurer depuis le disque donnerait
au coordinateur de fausses informations de verrouillage.

Au redemarrage, le coordinateur repart donc avec :

```text
writer  = null
readers = ensemble vide
```

Les nouveaux clients recuperent l'etat sauvegarde et demandent de nouveaux
verrous normalement.

## Limite assumee

Cette extension conserve la derniere version connue du coordinateur, pas une
copie forcee apres chaque `unlock`. C'est volontaire : envoyer chaque ecriture
au coordinateur supprimerait l'interet des verrous `WC` et `RC` en cache.

Donc, si un client modifie un objet uniquement dans son cache puis si le
coordinateur tombe avant une invalidation qui remonte cet etat, la derniere
modification locale peut etre perdue. C'est le compromis entre cache et
durabilite, deja choisi dans l'extension 2.

Pour le test de redemarrage, arreter les anciennes JVM clientes avant de
redemarrer le coordinateur. Elles gardent autrement des caches et des stubs
RMI associes a l'ancienne session du coordinateur.

## Fichiers modifies

- `src/jvn/JvnCoordImpl.java` : sauvegarde, chargement et reconstruction de
  l'etat durable du coordinateur.
- `README_EXTENSION_3_PANNE_COORDINATEUR.md` : justification et test.

## Compilation

Depuis `SOURCES` :

```bash
rm -rf /tmp/jvn-ext3
mkdir -p /tmp/jvn-ext3
javac -d /tmp/jvn-ext3 src/jvn/*.java src/irc/*.java src/burst/*.java
```

## Test de redemarrage

Choisir un fichier d'etat dedie au test :

```bash
export JVN_STATE_FILE=/tmp/jvn-ext3-state.bin
rm -f "$JVN_STATE_FILE"
```

1. Demarrer le coordinateur :

```bash
java -Djvn.coord.state.file="$JVN_STATE_FILE" -cp /tmp/jvn-ext3 jvn.JvnCoordLauncher
```

2. Dans un deuxieme terminal, lancer un ecrivain :

```bash
java -cp /tmp/jvn-ext3 burst.CrashWriterClient "etat-persistant"
```

3. Dans un troisieme terminal, effectuer une lecture. Cette lecture force la
conversion de l'ecrivain en lecteur et sauvegarde `etat-persistant` dans le
coordinateur :

```bash
java -cp /tmp/jvn-ext3 burst.ReadAfterFailureClient
```

4. Arreter les deux clients, puis arreter brutalement le coordinateur avec
`Ctrl+C`.

5. Redemarrer le coordinateur avec le meme fichier d'etat :

```bash
java -Djvn.coord.state.file="$JVN_STATE_FILE" -cp /tmp/jvn-ext3 jvn.JvnCoordLauncher
```

6. Dans une nouvelle JVM cliente, lire l'objet :

```bash
java -cp /tmp/jvn-ext3 burst.ReadAfterFailureClient
```

Le resultat attendu est :

```text
Read value: etat-persistant
```

Le fichier d'etat peut ensuite etre supprime :

```bash
rm -f "$JVN_STATE_FILE"
```
