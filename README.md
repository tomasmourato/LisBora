# LisBora

App Android para andar de bicicleta em Lisboa. Gera rotas que privilegiam a rede ciclável e as ruas calmas, e sugere passeios automáticos para o tempo que tens disponível (lazer, paisagens, rio, desporto, subidas, descidas…).

As rotas são calculadas **no telemóvel**: não há servidor nem contas. O plano completo está em [docs/PLANO.md](docs/PLANO.md).

## Estado

| Fase | Conteúdo | Estado |
|---|---|---|
| 0 | Ambiente e esqueleto do projeto | ✅ |
| 1 | Mapa offline e rede ciclável da CML | ✅ |
| 2 | Grafo e router (JVM) | ✅ |
| 3 | Planear A→B | ✅ |
| 4 | Descobrir (rotas automáticas) | — |
| 5 | Seguir rota | — |
| 6 | Atualização de dados sem nova versão da app | — |
| 7 | Polimento | — |

## Estrutura

- `app/` — app Android (Kotlin, Jetpack Compose, Material 3, Hilt).
- `core/routing/` — biblioteca Kotlin/JVM pura partilhada pela app e pelo pipeline (grafo, router, alternativas, perigos, ETA, pesquisa offline).
- `pipeline/` — ferramenta de linha de comandos que constrói os pacotes de dados (rede ciclável da CML, mapa base, grafo de rotas a partir do OpenStreetMap com o GraphHopper, índice de pesquisa). Não corre no telemóvel.

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

Para construir o índice de pesquisa offline (ruas, moradas, locais; usa o mesmo extrato OSM, ~30 s):

```bash
./gradlew :pipeline:run --args="search"
```

A app traz o grafo e o índice no pacote embutido (`app/src/main/assets/datapack`). Para os atualizar, usa `--out app/src/main/assets/datapack` nos dois comandos.

## Dados e licenças

O código ainda não tem licença: todos os direitos estão reservados. Os dados usados pela app mantêm as licenças próprias:

- **OpenStreetMap** — © contribuidores do OpenStreetMap, [ODbL](https://opendatacommons.org/licenses/odbl/). As bases de dados derivadas (grafo de rotas, índice de pesquisa) são distribuídas sob a ODbL.
- **Rede Ciclável de Lisboa** — © Câmara Municipal de Lisboa ([Lisboa Aberta](https://dados.cm-lisboa.pt/dataset/rede-ciclavel)), [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Os dados são normalizados e associados ao grafo OSM pela LisBora.
- **Elevação** — [Mapterhorn](https://mapterhorn.com), com dados da Direção-Geral do Território (LiDAR, [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)) e Copernicus GLO-30 (© DLR e.V. 2010-2014 e © Airbus Defence and Space GmbH 2014-2018, fornecido no âmbito do COPERNICUS pela União Europeia e a ESA).
- **Mapa base** — [Protomaps](https://protomaps.com), com dados © contribuidores do OpenStreetMap. Fontes Noto Sans (SIL Open Font License) e ícones derivados de [tangrams/icons](https://github.com/tangrams/icons) (MIT); ver [app/src/main/assets/map/NOTICE.md](app/src/main/assets/map/NOTICE.md).

A LisBora mostra o nível de stress e os perigos de cada rota, mas nenhuma rota é garantidamente segura. Circula sempre com atenção e cumpre o Código da Estrada.
