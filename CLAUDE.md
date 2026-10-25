# LisBora

Compila sempre a app e corre-a antes de finalizares a resposta.

App Android de rotas de bicicleta em Lisboa. O plano completo está em [docs/PLANO.md](docs/PLANO.md): as rotas são calculadas no telemóvel e não há servidor nem contas.

## Módulos
- `:app` — Android (Compose + Material 3, MVVM/UDF com `StateFlow`, Hilt via KSP). Pacotes por funcionalidade em `pt.lisbora.feature.*`; tema em `pt.lisbora.ui.theme`.
- `:core:routing` — Kotlin/JVM puro, **sem dependências Android** (grafo, router, ETA, Descobrir). É partilhado pela app e pelo pipeline.
- `:pipeline` — CLI JVM que constrói os pacotes de dados (CML, OSM, GraphHopper). Nunca corre no telemóvel.

## Convenções
- As versões estão fixadas em `gradle/libs.versions.toml`. Usa só versões estáveis.
- O AGP 9 compila o Kotlin por si (built-in Kotlin): **não** apliques `org.jetbrains.kotlin.android`.
- Textos da UI em pt-PT, em `app/src/main/res/values/strings.xml`. Código e comentários em inglês.
- Sem chaves nem segredos no código.
- Testes JVM: JUnit 6 (Jupiter) em `:core:routing` e `:pipeline`; JUnit 4 nos testes da app.

## Comandos (Windows, Git Bash)
O `JAVA_HOME` (JDK 21) e o `ANDROID_HOME` estão definidos nas variáveis de utilizador. O SDK também está em `local.properties` (não versionado). Em shells antigos, exporta o JDK à mão. Se exportares o `ANDROID_HOME`, usa o formato `C:/...`, porque um caminho `/c/...` faz falhar o AGP.

```bash
export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"
ANDROID_HOME="C:/Users/mrttm/AppData/Local/Android/Sdk"
```

- Testes JVM: `./gradlew :core:routing:test :pipeline:test :app:testDebugUnitTest`
- Compilar: `./gradlew :app:assembleDebug`
- Emulador: `$ANDROID_HOME/emulator/emulator -avd LisBora_API37 -no-snapshot-save -gpu host`
- Instalar e abrir: `$ANDROID_HOME/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk && $ANDROID_HOME/platform-tools/adb shell am start -n pt.lisbora/.MainActivity`
- Testes de UI (com o emulador ligado): `./gradlew :app:connectedDebugAndroidTest`
- Pipeline: `./gradlew :pipeline:run --args="help"`
