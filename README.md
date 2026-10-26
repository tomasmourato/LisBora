# LisBora

App Android para andar de bicicleta em Lisboa. Gera rotas que privilegiam a rede ciclável e as ruas calmas, e sugere passeios automáticos para o tempo que tens disponível (lazer, paisagens, rio, desporto, subidas, descidas…). Durante a volta, guia-te passo a passo, também por voz e com o ecrã desligado.

As rotas são calculadas **no telemóvel**: não há servidor nem contas. A Internet é opcional e serve para duas coisas: no Descobrir com GIRA, a app lê as bicicletas e docas livres das estações (dados da EMEL); uma vez por dia, em Wi-Fi, procura dados novos (rede ciclável, ruas, estações GIRA), publicados todas as semanas num release do GitHub. Sem rede, funciona com os dados que tem. O plano completo está em [docs/PLANO.md](docs/PLANO.md).

## Estado

| Fase | Conteúdo | Estado |
|---|---|---|
| 0 | Ambiente e esqueleto do projeto | ✅ |
| 1 | Mapa offline e rede ciclável da CML | ✅ |
| 2 | Grafo e router (JVM) | ✅ |
| 3 | Planear A→B | ✅ |
| 4 | Descobrir (rotas automáticas) | ✅ |
| 5 | Seguir rota | ✅ |
| 6 | Atualização de dados sem nova versão da app | ✅ |
| 7 | Polimento | ✅ |

## Estrutura

- `app/` — app Android (Kotlin, Jetpack Compose, Material 3, Hilt).
- `core/routing/` — biblioteca Kotlin/JVM pura partilhada pela app e pelo pipeline (grafo, router, alternativas, perigos, ETA, Descobrir, pesquisa offline).
- `baselineprofile/` — testes que gravam o perfil de arranque da app e medem o arranque.
- `pipeline/` — ferramenta de linha de comandos que constrói os pacotes de dados (rede ciclável da CML, mapa base, grafo de rotas a partir do OpenStreetMap com o GraphHopper, índice de pesquisa, pontos de interesse). Não corre no telemóvel.

## Compilar

Precisa do JDK 21 e do Android SDK (plataforma 37).

```bash
./gradlew :core:routing:test :pipeline:test :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Para construir o grafo de rotas (descarrega o extrato OSM de Portugal, ~430 MB, e os mosaicos de elevação; precisa de 8 GB de memória livre):

```bash
./gradlew :pipeline:run --args="build"
./gradlew :pipeline:goldenRoutes
```

O grafo fica em `pipeline/work/datapack/graph.lbg` e o relatório de qualidade em `pipeline/work/qa/report.md`.

Para construir o índice de pesquisa offline (ruas, moradas, locais) e os pontos de interesse do mapa e do Descobrir (`pois.geojson`); usa o mesmo extrato OSM, ~30 s:

```bash
./gradlew :pipeline:run --args="search"
```

Para verificar o Descobrir no grafo real (descobertas de referência, com tempos de cálculo):

```bash
./gradlew :pipeline:run --args="discover"
```

A app traz o grafo, o índice, os pontos de interesse e as estações GIRA no pacote embutido (`app/src/main/assets/datapack`). Para os atualizar, usa `--out app/src/main/assets/datapack` em `build`, `search` e `gira`.

Para o release (`:app:assembleRelease` ou `:app:bundleRelease`), a chave de assinatura, a publicação de um APK nos [releases do GitHub](https://github.com/tomasmourato/LisBora/releases) e na Google Play, e o perfil de arranque, ver [docs/PLAY_STORE.md](docs/PLAY_STORE.md).

Depois de instalada, a app atualiza os dados sozinha: o workflow [Dados](.github/workflows/data.yml) reconstrói o pacote às segundas, corre as verificações de qualidade e, se os dados mudaram, publica-o no release `datapack`. A app descarrega só os ficheiros que mudaram e passa a usá-los no arranque seguinte.

## Privacidade

A LisBora não tem contas, publicidade nem estatísticas de utilização, e a localização nunca sai do telemóvel. Ver a [política de privacidade](docs/PRIVACIDADE.md).

## Dados e licenças

O código ainda não tem licença: todos os direitos estão reservados. Os dados usados pela app mantêm as licenças próprias:

- **OpenStreetMap** — © contribuidores do OpenStreetMap, [ODbL](https://opendatacommons.org/licenses/odbl/). As bases de dados derivadas (grafo de rotas, índice de pesquisa, pontos de interesse) são distribuídas sob a ODbL.
- **Rede Ciclável de Lisboa** — © Câmara Municipal de Lisboa ([Lisboa Aberta](https://dados.cm-lisboa.pt/dataset/rede-ciclavel)), [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Os dados são normalizados e associados ao grafo OSM pela LisBora.
- **Elevação** — [Mapterhorn](https://mapterhorn.com), com dados da Direção-Geral do Território (LiDAR, [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)) e Copernicus GLO-30 (© DLR e.V. 2010-2014 e © Airbus Defence and Space GmbH 2014-2018, fornecido no âmbito do COPERNICUS pela União Europeia e a ESA).
- **Estações GIRA** — EMEL, lidas da plataforma da GIRA (a mesma que a app oficial usa; não é uma API publicada).
- **Mapa base** — [Protomaps](https://protomaps.com), com dados © contribuidores do OpenStreetMap. Fontes Noto Sans (SIL Open Font License) e ícones derivados de [tangrams/icons](https://github.com/tangrams/icons) (MIT); ver [app/src/main/assets/map/NOTICE.md](app/src/main/assets/map/NOTICE.md).

A LisBora mostra o nível de stress e os perigos de cada rota, mas nenhuma rota é garantidamente segura. Circula sempre com atenção e cumpre o Código da Estrada.
