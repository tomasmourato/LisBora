# LisBora

App Android para andar de bicicleta em Lisboa. Gera rotas que privilegiam a rede ciclável e as ruas calmas, e sugere passeios automáticos para o tempo que tens disponível (lazer, paisagens, rio, desporto, subidas, descidas…).

As rotas são calculadas **no telemóvel**: não há servidor nem contas. O plano completo está em [docs/PLANO.md](docs/PLANO.md).

## Estado

| Fase | Conteúdo | Estado |
|---|---|---|
| 0 | Ambiente e esqueleto do projeto | ✅ |
| 1 | Mapa offline e rede ciclável da CML | 🚧 |
| 2 | Grafo e router (JVM) | — |
| 3 | Planear A→B | — |
| 4 | Descobrir (rotas automáticas) | — |
| 5 | Seguir rota | — |
| 6 | Atualização de dados sem nova versão da app | — |
| 7 | Polimento | — |

## Estrutura

- `app/` — app Android (Kotlin, Jetpack Compose, Material 3, Hilt).
- `core/routing/` — biblioteca Kotlin/JVM pura partilhada pela app e pelo pipeline (grafo, router, ETA).
- `pipeline/` — ferramenta de linha de comandos que constrói os pacotes de dados (rede ciclável da CML, OpenStreetMap). Não corre no telemóvel.

## Compilar

Precisa do JDK 21 e do Android SDK (plataforma 37).

```bash
./gradlew :core:routing:test :pipeline:test :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

## Dados e licenças

O código ainda não tem licença: todos os direitos estão reservados. Os dados usados pela app mantêm as licenças próprias:

- **OpenStreetMap** — © contribuidores do OpenStreetMap, [ODbL](https://opendatacommons.org/licenses/odbl/). As bases de dados derivadas (grafo de rotas, índice de pesquisa) são distribuídas sob a ODbL.
- **Rede Ciclável de Lisboa** — © Câmara Municipal de Lisboa ([Lisboa Aberta](https://dados.cm-lisboa.pt/dataset/rede-ciclavel)), [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Os dados são normalizados e associados ao grafo OSM pela LisBora.
- **Mapa base** — [Protomaps](https://protomaps.com), com dados © contribuidores do OpenStreetMap.

A LisBora mostra o nível de stress e os perigos de cada rota, mas nenhuma rota é garantidamente segura. Circula sempre com atenção e cumpre o Código da Estrada.
