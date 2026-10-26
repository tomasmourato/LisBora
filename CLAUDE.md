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
- Sem chaves nem segredos no código. Exceção decidida pelo utilizador (26/10/2026): `VaimooGiraFeed` traz a chave pública da app oficial da GIRA, para ler a disponibilidade das estações no Firestore da VAIMOO. É uma API não documentada: se falhar, a app usa as estações do pacote, sem contagens.
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
- Testes de UI (com o emulador ligado): `./gradlew :app:connectedDebugAndroidTest`. No fim, desinstalam a app; volta a instalá-la antes de a correres.
- Pipeline: `./gradlew :pipeline:run --args="help"`. Os caminhos são relativos à raiz.
- Grafo de rotas: `./gradlew :pipeline:run --args="build"` (usa 8 GB; ~1,5 min com caches) e `./gradlew :pipeline:goldenRoutes` (mostra também as alternativas e o tempo de cálculo). Para medir uma mudança nos pesos do router: `--args="golden-routes --amostra 300"` (300 viagens aleatórias no centro, sempre as mesmas; compara antes e depois). Caches em `pipeline/work/{osm,gh,dem}`; relatório em `pipeline/work/qa/report.md`; o grafo só vai para `--out` (por omissão `pipeline/work/datapack`) se o QA passar, mas fica sempre em `pipeline/work/graph-candidate.lbg`. Para ver uma rota troço a troço: `--args="golden-routes --detalhe sim --graph pipeline/work/graph-candidate.lbg"`.
- Índice de pesquisa e pontos de interesse: `./gradlew :pipeline:run --args="search"` (~30 s com o extrato em cache). Escreve `search.lbs` e `pois.geojson` só se as pesquisas de referência (`pipeline/config/golden-searches.json`) passarem; os candidatos ficam sempre em `pipeline/work/search-candidate.lbs` e `pipeline/work/pois-candidate.geojson`. Para ver como um local está mapeado no OSM: `--args='search --mostrar "lx factory"'`.
- Descobrir no grafo real: `./gradlew :pipeline:run --args="discover"` corre as descobertas de referência (`pipeline/config/golden-discoveries.json`; com `"gira": true` verificam também que a rota nunca sai do concelho de Lisboa) com o grafo e os POIs de `pipeline/work/datapack` (ou `--graph`/`--pois`) e mostra os tempos de cálculo. Para medir uma mudança no `DiscoveryEngine`: `--args="discover --amostra 200"` (sempre as mesmas viagens; compara antes e depois); `--detalhe sim` mostra todas as candidatas e `--fios 1` o tempo num só núcleo.
- Simular uma volta (navegação): `./gradlew :pipeline:run --args="route --de 38.7066,-9.1445 --para 38.7101,-9.1335 --graph app/src/main/assets/datapack/graph.lbg --gpx pipeline/work/ride.gpx"` (a mesma rota que a app calcula com as preferências por omissão, e as instruções) e depois `python pipeline/tools/simulate-ride.py pipeline/work/ride.gpx --speed 6` com a navegação iniciada na app; `--detour-at 600` sai da rota para testar o recálculo.
- Localização simulada no emulador (o `geo fix` não funciona nesta imagem):
  `adb shell appops set com.android.shell android:mock_location allow`, depois `adb shell cmd location providers add-test-provider gps` e `adb shell cmd location providers set-test-provider-location gps --location 38.7139,-9.1427`.

## Pacote de dados embutido (`app/src/main/assets/datapack`)
A app copia-o para `filesDir/datapack` no arranque, só os ficheiros cujo SHA-256 mudou, segundo o `manifest.json`. Cada comando do pipeline regenera o manifesto.
- Rede ciclável da CML: `./gradlew :pipeline:run --args="fetch-cml --out app/src/main/assets/datapack"`
- Grafo (`graph.lbg`, ~8 MB, com os atributos cénicos do Descobrir), índice de pesquisa (`search.lbs`, ~5 MB) e pontos de interesse (`pois.geojson`, ~0,7 MB), versionados no git como o mapa base: `build --out app/src/main/assets/datapack` e `search --out app/src/main/assets/datapack`. Se mudares a rede da CML, reconstrói o grafo a seguir, para o mapa e o router usarem a mesma versão.
- Mapa base (cerca de 18 MB, versionado no git: atualiza-o com parcimónia): `./gradlew :pipeline:run --args="basemap --out app/src/main/assets/datapack"`. Precisa do `pmtiles` CLI em `pipeline/work/tools/`.
- Estilos, fontes e sprites (`app/src/main/assets/map`): `cd pipeline/tools/style && npm ci && npm run generate`.
