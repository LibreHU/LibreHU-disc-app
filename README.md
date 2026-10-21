# LibreHU Disc

Lecteur de **CD audio**, de **CD / DVD de données** et de **DVD vidéo** pour un lecteur optique USB branché sur
l'autoradio Jancar / Autochips AC8257 (UJC201). Thème clair / sombre et couleur d'accent de
[LibreHU Launcher](https://github.com/LibreHU/LibreHU-Launcher-App), comme les autres applis LibreHU.

**Non testé sur l'autoradio à ce stade.**

## Ce que fait l'appli

| Disque | Lecture |
|---|---|
| **CD audio** | lu par l'appli elle-même : secteurs audio bruts (`CDROMREADAUDIO`, 44,1 kHz) envoyés à Android, pistes enchaînées sans blanc, lecture automatique à l'insertion (option), saut de piste, avance dans la piste, éjection |
| **CD / DVD de données** (MP3, FLAC, OGG, M4A, WAV…) | disque monté en lecture seule (UDF puis ISO 9660 / Joliet), navigation dans les dossiers, lecture des fichiers audio par l'appli (« Tout lire »), les autres fichiers (vidéos, images…) ouverts dans l'appli choisie |
| **DVD vidéo** | ouvert dans **VLC** avec ses menus : l'appli lui donne le disque entier comme une image ISO (`content://org.librehu.disc.files/raw/disc.iso`) ; VLC pour Android lit les ISO de DVD, pas un dossier `VIDEO_TS` seul |

- **Session média** : touches du volant, [LibreHU EasySource](https://github.com/LibreHU/LibreHU-EasySource-app)
  (l'appli est une source média, la liste des pistes du CD est visible), notification avec précédent / lecture /
  suivant.
- **Focus audio** : pause quand une autre source joue (appel, navigation, autre appli), reprise après une
  interruption courte.
- **Deux styles** : *Moderne* (thème LibreHU, suit le lanceur) ou **Rétro**, façade d'autoradio CD des années
  1990-2000 : afficheur à segments (police [DSEG](https://github.com/keshikan/DSEG), segments éteints visibles par
  transparence et halo lumineux), témoins LOAD / CD / DVD / DATA / MP3 / ▶ / ‖, piste et temps en 7 segments, texte
  défilant en 14 segments, **analyseur de spectre 16 bandes** calculé sur le son du CD (FFT, décalé de la latence de
  sortie pour suivre ce qu'on entend), touches en relief (◄◄ ►‖ ►► ▲ LIST COLOR MODE), afficheur ambre, bleu glacier,
  vert ou rouge (COLOR). Bouton *Rétro* de l'écran moderne, touche MODE pour revenir.
- **Root demandé au premier démarrage de LibreHU Launcher** avec les autres applis LibreHU
  (`org.librehu.action.REQUEST_ROOT`, comme LibreHU FM).

## Ce qu'il faut

- **Le noyau LibreHU** (`LibreHU-main` à partir du run 66, ou `connectivity-builtin` à partir du run 67) : pilotes
  `sr` (lecteur optique), `sg` et UDF ; le noyau stock de Jancar n'a pas `CONFIG_BLK_DEV_SR`, le lecteur n'y est pas
  reconnu.
- **Le root** (KernelSU Next, Magisk…) : le nœud du lecteur (`/dev/block/sr0`) est réservé à root et Android ne monte
  pas les disques optiques. L'appli ouvre le nœud (`chmod 0666`, une fois par branchement) et monte les disques de
  données sur `/mnt/librehu_disc` avec `su`. Autorisez-la dans le gestionnaire root la première fois.
- Un port USB en **mode hôte** et un lecteur CD / DVD USB alimenté (certains lecteurs fins demandent un câble en Y).
- **VLC** pour les DVD vidéo.

## Limites connues

- **DVD du commerce protégés (CSS)** : VLC a de quoi les déchiffrer, mais l'image ISO transmise par l'appli passe par
  le pilote du lecteur sans l'authentification CSS : des DVD protégés risquent de ne pas se lire. Les DVD non protégés
  (gravés, vidéos perso) se lisent.
- **CD audio** : pas encore de titres (CD-Text, base de données en ligne) : les pistes s'appellent « Piste N ».
- **Montage visible par les autres applis** : le disque est monté dans l'espace de montage global (`su -mm`) ; selon le
  gestionnaire root, les autres applis peuvent ne pas le voir. L'appli, elle, le voit toujours.
- Le lecteur n'est surveillé que quand l'appli est ouverte ou qu'elle joue.

## Pour les développeurs

- `app/src/main/cpp/librehu_disc.c` : les ioctl CD-ROM du pilote `sr` (état du lecteur et du disque, table des
  matières, secteurs audio, capacité, éjection), inaccessibles depuis Java.
- `drive/DiscDrive.kt` : surveillance du lecteur (`/sys/block/sr*`), accès root, montage.
- `player/Playback.kt` : lecteur (CD : fil de lecture vers `AudioTrack` ; fichiers : `MediaPlayer`), session média,
  focus audio ; `player/DiscService.kt` : service de lecture, notification, arbre `MediaBrowserService`.
- `DiscFileProvider.kt` : fichiers du disque et disque entier (ISO) pour VLC et les autres applis.

Compilation : `./gradlew :app:assembleRelease` (JDK 17+, SDK Android, NDK installé par Gradle). La CI
(`.github/workflows/build.yml`) publie l'APK en release `latest-<branche>` pour
[LibreHU OTA](https://github.com/LibreHU/LibreHU-OTA-app), comme les autres applis LibreHU.

Licence : GPL-2.0 (voir `LICENSE`). Police DSEG © keshikan, SIL Open Font License 1.1 (`third_party/DSEG-LICENSE.txt`).
