# LisBora

Compila sempre a app e corre-a antes de finalizares a resposta.

App Android de rotas de bicicleta em Lisboa. O plano completo está em [docs/PLANO.md](docs/PLANO.md): as rotas são calculadas no telemóvel e não há servidor nem contas.

## Módulos
- `:app` — Android (Compose + Material 3, MVVM/UDF com `StateFlow`, Hilt via KSP). Pacotes por funcionalidade em `pt.lisbora.feature.*`; tema em `pt.lisbora.ui.theme`.
- `:core:routing` — Kotlin/JVM puro, **sem dependências Android** (grafo, router, ETA, Descobrir). É partilhado pela app e pelo pipeline.
- `:pipeline` — CLI JVM que constrói os pacotes de dados (CML, OSM, GraphHopper). Nunca corre no telemóvel.
- `:baselineprofile` — testes Macrobenchmark que gravam o perfil de arranque da app e medem o arranque. Não vai na app.

## Convenções
- As versões estão fixadas em `gradle/libs.versions.toml`. Usa só versões estáveis.
- O AGP 9 compila o Kotlin por si (built-in Kotlin): **não** apliques `org.jetbrains.kotlin.android`.
- Textos da UI em pt-PT, em `app/src/main/res/values/strings.xml`. Código e comentários em inglês.
- Sem chaves nem segredos no código. Exceção decidida pelo utilizador (26/10/2026): `VaimooGiraFeed` (app) e `VaimooGiraSource` (comando `gira` do pipeline) trazem a chave pública da app oficial da GIRA, para ler as estações no Firestore da VAIMOO. É uma API não documentada: se falhar, a app usa as estações do pacote, sem contagens, e o `gira` não escreve nada (o pacote mantém a lista que tem).
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
- Testes de UI (com o emulador ligado): `./gradlew :app:connectedDebugAndroidTest`. No fim, desinstalam a app; volta a instalá-la antes de a correres. Correm com as verificações de acessibilidade (Accessibility Test Framework) ligadas: um elemento sem rótulo, pequeno demais ou com pouco contraste faz falhar o teste.
- Release: `./gradlew :app:assembleRelease` (APK) ou `:app:bundleRelease` (App Bundle para a Play). É assinado com a chave de release `C:/Users/mrttm/lisbora-release.jks`; a palavra-passe está só nas propriedades `lisbora.signing.*` de `~/.gradle/gradle.properties`. **Nunca cries outra chave**: as versões novas só se instalam por cima das publicadas se tiverem a mesma. Sem essas propriedades (noutro PC ou no CI), o release fica assinado com a chave de debug: serve para testar, mas não se instala por cima do APK publicado e a Play recusa-o. O release e o debug não se instalam um por cima do outro: desinstala primeiro (`adb uninstall pt.lisbora`).
- APK no GitHub (como o [v0.1.0](https://github.com/tomasmourato/LisBora/releases/tag/v0.1.0)):
  1. Sobe o `versionCode` e o `versionName`.
  2. Corre `assembleRelease`.
  3. Confirma a assinatura com o `apksigner verify --print-certs`.
  4. Experimenta no emulador.
  5. Publica com `gh release create vX.Y.Z LisBora-X.Y.Z.apk --target <SHA completo> --title "LisBora X.Y.Z" --notes-file ... --latest`.

  Os passos completos estão em [docs/PLAY_STORE.md](docs/PLAY_STORE.md). Não mexas no release `datapack`.
- Perfil de arranque (Baseline Profile), com o emulador ligado: `./gradlew :baselineprofile:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=pt.lisbora.baselineprofile.BaselineProfileGenerator` grava o arranque, uma pesquisa e o Descobrir no build `benchmark` (como o release, sem R8). Copia o `*-baseline-prof.txt` de `baselineprofile/build/outputs/connected_android_test_additional_output/benchmark/connected/<dispositivo>/` para `app/src/main/baseline-prof.txt`. Volta a gravá-lo quando o arranque mudar muito. Para medir o arranque sem e com o perfil: `...class=pt.lisbora.baselineprofile.StartupBenchmark`. No fim, desinstalam a app.
- Pipeline: `./gradlew :pipeline:run --args="help"`. Os caminhos são relativos à raiz.
- Grafo de rotas: `./gradlew :pipeline:run --args="build"` (usa 8 GB; ~1,5 min com caches) e `./gradlew :pipeline:goldenRoutes` (mostra também as alternativas e o tempo de cálculo). Para medir uma mudança nos pesos do router: `--args="golden-routes --amostra 300"` (300 viagens aleatórias no centro, sempre as mesmas; compara antes e depois). Caches em `pipeline/work/{osm,gh,dem}`; relatório em `pipeline/work/qa/report.md`; o grafo só vai para `--out` (por omissão `pipeline/work/datapack`) se o QA passar, mas fica sempre em `pipeline/work/graph-candidate.lbg`. Para ver uma rota troço a troço: `--args="golden-routes --detalhe sim --graph pipeline/work/graph-candidate.lbg"`.
- Índice de pesquisa e pontos de interesse: `./gradlew :pipeline:run --args="search"` (~30 s com o extrato em cache). Escreve `search.lbs` e `pois.geojson` só se as pesquisas de referência (`pipeline/config/golden-searches.json`) passarem; os candidatos ficam sempre em `pipeline/work/search-candidate.lbs` e `pipeline/work/pois-candidate.geojson`. Para ver como um local está mapeado no OSM: `--args='search --mostrar "lx factory"'`.
- Descobrir no grafo real: `./gradlew :pipeline:run --args="discover"` corre as descobertas de referência (`pipeline/config/golden-discoveries.json`; com `"gira": true` verificam também que a rota nunca sai do concelho de Lisboa) com o grafo e os POIs de `pipeline/work/datapack` (ou `--graph`/`--pois`) e mostra os tempos de cálculo. Para medir uma mudança no `DiscoveryEngine`: `--args="discover --amostra 200"` (sempre as mesmas viagens; compara antes e depois); `--detalhe sim` mostra todas as candidatas e `--fios 1` o tempo num só núcleo.
- Simular uma volta (navegação): `./gradlew :pipeline:run --args="route --de 38.7066,-9.1445 --para 38.7101,-9.1335 --graph app/src/main/assets/datapack/graph.lbg --gpx pipeline/work/ride.gpx"` (a mesma rota que a app calcula com as preferências por omissão, e as instruções) e depois `python pipeline/tools/simulate-ride.py pipeline/work/ride.gpx --speed 6` com a navegação iniciada na app; `--detour-at 600` sai da rota para testar o recálculo.
- Atualização de dados (Fase 6): o workflow `.github/workflows/data.yml` corre às segundas (e a pedido, em Actions → Dados) e publica no release `datapack` do GitHub só se os dados mudaram e o QA passou. Os passos são os mesmos à mão: `pull --out DIR` (o pacote publicado), `fetch-cml`, `gira`, `build`, `search`, `discover` e `release --out DIR` (prepara em `pipeline/work/release` só os ficheiros novos, com o nome do conteúdo, e o manifesto; nada, se só mudaram datas nos `*.meta.json`). O Gradle aceita `-Plisbora.semApp=true` para correr só o pipeline, sem o Android SDK.
- Testar a atualização no emulador: `python -m http.server 8000 --bind 127.0.0.1 --directory pipeline/work/serve` com o `manifest.json` e os ficheiros de `pipeline/work/release` (os de `assets/` na mesma pasta), `adb reverse tcp:8000 tcp:8000` e a app compilada com `./gradlew :app:assembleDebug -Plisbora.dataManifestUrl=http://localhost:8000/manifest.json` (só o build de debug aceita HTTP, e só para `localhost`; o Android 17 não deixa as apps chegar ao 10.0.2.2 sem a permissão de rede local). Nas preferências, "Procurar dados novos" e depois "Reiniciar agora". O worker diário também corre logo no arranque, em Wi-Fi; o log `LisBoraData` diz o que encontrou.
- Navegação: o estado da volta vive na `NavigationSession` (singleton), não no ecrã. O `NavigationService` (serviço em primeiro plano, tipo localização) mantém a volta com o ecrã desligado, mostra a próxima indicação na notificação e fala: o `VoicePrompts` decide o que dizer e o `VoiceGuide` usa o TTS do telemóvel. O log `LisBoraVoice` mostra cada frase dita.
- Localização simulada no emulador (o `geo fix` não funciona nesta imagem):
  `adb shell appops set com.android.shell android:mock_location allow`, depois `adb shell cmd location providers add-test-provider gps` e `adb shell cmd location providers set-test-provider-location gps --location 38.7139,-9.1427`.

## Pacote de dados embutido (`app/src/main/assets/datapack`)
A app guarda os pacotes em `filesDir/datapack`: cada ficheiro uma vez em `objects/`, com o nome do conteúdo (`graph.625e72943f43be77.lbg`), e o `state.json` diz qual está em uso, o anterior (para voltar atrás) e um descarregado à espera do próximo arranque. O pacote só muda no arranque, antes de o mapa, o router e a pesquisa o lerem. O embutido é copiado no primeiro arranque, ou quando uma versão nova da app traz dados mais recentes do que os descarregados (compara-se o `dataVersion` do `manifest.json`). Cada comando do pipeline regenera o manifesto; o `dataVersion` só muda quando muda algum ficheiro. Se mudares um formato binário (`GraphFormat`, `SearchIndexFormat`), sobe o `FORMAT_VERSION`: o manifesto passa a pedi-lo e as apps antigas deixam de instalar pacotes que não sabem ler.
- Rede ciclável da CML: `./gradlew :pipeline:run --args="fetch-cml --out app/src/main/assets/datapack"`
- Estações GIRA (lista da EMEL, ~43 KB, sem contagens): `./gradlew :pipeline:run --args="gira --out app/src/main/assets/datapack"`. Só escreve se a lista parecer completa (150 estações ou mais, todas na região, e no máximo 20% menos do que a do pacote).
- Grafo (`graph.lbg`, ~8 MB, com os atributos cénicos do Descobrir), índice de pesquisa (`search.lbs`, ~5 MB) e pontos de interesse (`pois.geojson`, ~0,7 MB), versionados no git como o mapa base: `build --out app/src/main/assets/datapack` e `search --out app/src/main/assets/datapack`. Se mudares a rede da CML, reconstrói o grafo a seguir, para o mapa e o router usarem a mesma versão.
- Mapa base (cerca de 18 MB, versionado no git: atualiza-o com parcimónia): `./gradlew :pipeline:run --args="basemap --out app/src/main/assets/datapack"`. Precisa do `pmtiles` CLI em `pipeline/work/tools/`.
- Estilos, fontes e sprites (`app/src/main/assets/map`): `cd pipeline/tools/style && npm ci && npm run generate`.
