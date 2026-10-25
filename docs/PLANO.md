# LisBora — Plano de desenvolvimento

## Contexto

A LisBora é uma app Android para andar de bicicleta em Lisboa, com um mapa ao estilo do Google Maps e uma interface pensada para ciclistas. Faz duas coisas:
1. **Planear A→B**: o utilizador indica a partida e o destino e recebe rotas que privilegiam ciclovias e ruas calmas.
2. **Descobrir**: sem destino definido, a app gera uma rota interessante para um tempo disponível (por exemplo, 45 min), um motivo (lazer, paisagens, rio, desporto, subidas, descidas, família, cultura) e as preferências do utilizador.

Ponto de partida: a pasta `C:\Users\mrttm\projects\LisBora` só tem `claude/CLAUDE.md` ("Compila sempre a app e corre-a antes de finalizares a resposta"). Não é um repositório git. A máquina tem git, Node, Python e winget, mas **não tem JDK, SDK do Android, emulador nem Docker**. Tem 32 GB de RAM, um i7-13650HX e o hipervisor ativo.

Decisões tomadas contigo:
- **Tudo no telemóvel**: as rotas são calculadas no dispositivo e não há servidor.
- **Navegação v1**: seguir a rota no mapa, sem voz.
- **Ambiente**: instalo-o eu via winget, com a tua aprovação em cada instalação.
- **Contas**: não há. Os dados ficam todos locais.

### Dados verificados (rede ciclável da CML)
- Serviço ArcGIS: `https://services.arcgis.com/1dSrzEWVQn5kHHyK/arcgis/rest/services/Ciclovias/FeatureServer/0`. Licença **CC BY 4.0** (Município de Lisboa / Lisboa Aberta). Última edição a **23/10/2026**. Máximo de 2000 registos por pedido, por isso é preciso paginar. Pede-se com `outSR=4326`.
- Tem 1087 segmentos e cerca de 298 km, todos com `SITUACAO = Executado`. Os campos úteis são `TIPOLOGIA`, `NIVEL_SEGREGACAO`, `HIERARQUIA`, `DESIGNACAO`, `FREGUESIA`, `ANO` e `GlobalID`.

| Classe LisBora | TIPOLOGIA da CML (segregação) | km |
|---|---|---|
| `SEGREGADA` | Pista Ciclável Bi/Unidirecional e Contrassentido (separação física) | ~119 |
| `FAIXA` | Faixa Ciclável e Contrassentido (separação visual) | ~8 |
| `PARTILHADA_30` | 30+Bici e Zona de Coexistência | ~87 |
| `CICLOPEDONAL` | Percurso Ciclopedonal | ~24 |
| `TRILHO` | Trilho (Monsanto, etc.) | ~59 |

## Decisões técnicas (onde me afasto das sugestões do ChatGPT)

| Área | Escolha | Porquê |
|---|---|---|
| Motor de rotas | **Router próprio em Kotlin a correr no dispositivo**, num módulo JVM puro. O GraphHopper 11.1 só é usado **na construção dos dados**, no PC ou no CI. | Pediste "tudo no telemóvel". O GraphHopper deixou de suportar Android oficialmente depois da versão 1.0. Além disso, os modelos personalizados dele são compilados em tempo de execução com o **Janino** (bytecode JVM), o que não funciona em ART. Usá-lo na construção aproveita o parsing de OSM, que está muito testado (acessos, sentidos únicos incluindo `oneway:bicycle`, barreiras, piso), e a elevação. No telemóvel corre um A* simples sobre um grafo pequeno, só da região de Lisboa, que podemos controlar e testar por completo. |
| Ciclovias no encaminhamento | **Conflação ao nível de cada aresta** entre as linhas da CML e o grafo OSM, feita no pipeline. Cada aresta fica com a classe de infraestrutura da tabela acima. | Não dependemos do OSM estar completo. O mapa e o router usam **a mesma versão** dos dados da CML. |
| Mapa | MapLibre Android 13.x (versão estável fixada) dentro de `AndroidView`, com um extrato **Protomaps PMTiles** offline (`pmtiles://file://`, suportado desde a 11.7.0) e um estilo próprio para ciclismo. | Não precisa de chaves nem de servidor e funciona offline. O `maplibre-compose` ainda está na 0.x e a API não é estável. |
| Pesquisa de moradas | Índice **SQLite FTS4 offline**, gerado a partir do OSM (ruas, números de porta, POIs). | A instância pública do Photon é "só para demonstração". Assim não há API nem chaves. |
| Preferências | **DataStore**, não Room | Room fica reservado para favoritos, histórico e rotas guardadas. |
| Rede | Só **OkHttp**, para descarregar pacotes de dados. Não uso Retrofit. | Não há API REST. |
| Backend | **Nenhum**. Os pacotes de dados são estáticos e versionados, alojados em GitHub Releases (ou Cloudflare R2). | Custo zero e nenhuma infraestrutura para manter. |
| Supabase | Fica fora da v1. | Decidido contigo. |
| Mantém-se | Kotlin, Compose + Material 3, MVVM/UDF com `StateFlow`, Coroutines/Flow, Hilt (KSP), Room, JUnit, testes de UI do Compose | — |

Versões: na implementação vou buscar a última estável nas páginas oficiais e fixo-a em `gradle/libs.versions.toml`. Referências: JDK 21, GraphHopper 11.1 (precisa de Java 17 ou superior; o master já exige Java 25, por isso não o usamos) e MapLibre Android 13.x. Se o renderer Vulkan der problemas no emulador, mudo para o artefacto OpenGL.

## Arquitetura

```
[pipeline (JVM, no PC/CI)]
  OSM Portugal (Geofabrik) + Rede Ciclável CML (ArcGIS) + elevação + region.geojson
    → GraphHopper import (perfil bike, elevação)
    → exportação do grafo da região (formato binário próprio)
    → conflação CML→arestas, elétricos, piso, stress, atributos "cénicos"
    → POIs, índice de pesquisa, ciclovias.geojson, basemap.pmtiles
    → relatório QA + rotas de referência → datapack vN + manifest.json
           │  (estático, versionado, sha256)
           ▼
[app Android]  DataPackRepository → GraphStore(:core:routing) → RoutingEngine / DiscoveryEngine
               MapLibre (basemap + ciclovias + rota) · Room · DataStore · Fused Location
```

### Estrutura do repositório (um único build Gradle)
- `:core:routing` — Kotlin/JVM puro, sem Android. Inclui:
  - formato do grafo (leitor e escritor)
  - índice espacial
  - A* sobre arestas
  - pesagem e modelo de ETA
  - alternativas
  - `DiscoveryEngine`
  - `InstructionBuilder` em pt-PT

  É partilhado pela app e pelo pipeline.
- `:pipeline` — aplicação CLI em Kotlin/JVM que depende do `graphhopper-core` 11.1 e do JTS. Configuração em `pipeline/config/`: `region.geojson`, `overrides.geojson` (correções manuais) e `golden-routes.yaml`.
- `:app` — Android, com pacotes por funcionalidade:
  - `feature/map`, `feature/search`, `feature/planner`, `feature/discover`, `feature/navigation`, `feature/saved`, `feature/settings`, `feature/about`
  - `data/` com os repositórios
  - `di/` com os módulos Hilt
  - `ui/theme`

Ficheiros principais a criar (representativos):
- `settings.gradle.kts`, `gradle/libs.versions.toml`
- `core/routing/src/main/kotlin/pt/lisbora/routing/{graph/GraphFormat.kt, graph/GraphStore.kt, RoutingEngine.kt, weighting/BikeWeighting.kt, eta/RiderModel.kt, discover/DiscoveryEngine.kt, instructions/InstructionBuilder.kt}`
- `pipeline/src/main/kotlin/pt/lisbora/pipeline/{FetchCml.kt, ImportOsm.kt, ExportGraph.kt, Conflate.kt, BuildPois.kt, BuildSearchIndex.kt, QaReport.kt, PackDataset.kt}`
- `app/src/main/java/pt/lisbora/{MainActivity.kt, data/DataPackRepository.kt, data/RoutingRepository.kt, feature/map/LisboraMap.kt, ...}`
- `CLAUDE.md` passa para a raiz (hoje está em `claude/CLAUDE.md`, que não é carregado automaticamente). Fica com a regra "compilar e correr" e as convenções do projeto.

## Dados e pipeline

1. **Região**: `region.geojson` cobre o concelho de Lisboa, uma margem de cerca de 3 km (Algés, Oeiras ribeirinha, Amadora, Odivelas e Loures sul) e Cacilhas/Almada ribeirinha (ligação por ferry). A região é configurável.
2. **OSM**: extrato de Portugal da Geofabrik, importado pelo GraphHopper com o perfil bike e `turn_costs`. A exportação guarda só as arestas dentro da região e o maior componente fortemente ligado.
3. **Elevação**: avaliar o fornecedor `pmtiles` do GraphHopper (mapterhorn, 2026) contra `srtm`/`cgiar`, com suavização. Validar em subidas conhecidas: Calçada da Glória, Av. da Liberdade, Av. Almirante Reis e Rua da Senhora do Monte. Pontes e túneis são interpolados.
4. **Conflação CML** (dentro do `ExportGraph`, sem plugins no GraphHopper):
   - Amostrar as linhas da CML a cada ~5 m.
   - Atribuir cada amostra **apenas à aresta mais próxima** dentro de ~12 m e com rumo compatível (menos de 30°). Dar prioridade a vias OSM próprias para bicicleta. Isto evita marcar a estrada paralela a uma pista separada.
   - Uma aresta fica com a classe da CML se tiver 60% ou mais de cobertura. Ciclovias OSM que não estão na CML ficam como `OSM_CICLOVIA`.
   - O `overrides.geojson` permite correções manuais.
   - O contrassentido **não altera acessos** automaticamente. As divergências com o `oneway:bicycle` do OSM vão para o relatório e só entram por override manual.
5. **Atributos Lisboa por aresta**:
   - carris de elétrico (correspondência espacial com `railway=tram`)
   - calçada (`surface=sett/cobblestone`)
   - declive médio e máximo
   - escadas e desmontar (`get_off_bike`)
   - stress de tráfego de 1 a 4, calculado com `road_class`, `maxspeed`, `lanes`, infraestrutura e elétrico
   - flags cénicas: junto ao rio, em parque ou zona verde, perto de miradouro
   - nome da rua
   - semáforos, para o atraso no ETA
6. **POIs** (OSM): miradouros, parques, bebedouros, estacionamento de bicicletas, oficinas e estações GIRA (`amenity=bicycle_rental`), monumentos.
7. **Pesquisa**: `search.db` (SQLite FTS4, `unicode61 remove_diacritics`), aberto só para leitura com o SQLite do sistema.
8. **Mapa base**: `pmtiles extract` do build diário do Protomaps para o bbox da região. O estilo é gerado com `@protomaps/basemaps`, com fontes e sprites incluídos e versões claro e escuro.
9. **Relatório QA**, que falha o build se piorar:
   - % de km da CML associados a arestas (meta inicial de 95% ou mais, ajustada depois da primeira medição)
   - GeoJSON das falhas
   - diferenças face à versão anterior da CML (km novos ou removidos)
   - resultado das rotas de referência
10. **Pacote de dados**:
    - Ficheiros: `graph.lbg`, `ciclovias.geojson`, `pois.geojson`, `search.db`, `basemap.pmtiles`, `attribution.json`.
    - O `manifest.json` leva `dataVersion`, `formatVersion`, sha256, tamanhos e as datas das fontes.
    - O pacote de base vem embutido na app. As atualizações chegam por WorkManager (só em Wi-Fi): a app verifica o sha256, troca de pasta de forma atómica e mantém a versão anterior para poder voltar atrás. **A rede ciclável atualiza-se sem publicar uma nova versão da app.**

## Encaminhamento no dispositivo (`:core:routing`)

- **Grafo**:
  - Binário little-endian com cabeçalho (`magic`, `formatVersion`, `dataVersion`, bbox).
  - Nós: coordenadas em int E7 e elevação.
  - Arestas: atributos compactados, geometria e índice de nomes, com adjacência CSR.
  - Inclui uma grelha espacial para o snapping e as restrições de viragem.
  - Carrega-se para arrays primitivos em menos de 200 ms (a confirmar).
- **Algoritmo**:
  - **A* sobre arestas** (edge-based). Assim respeita restrições de viragem e penaliza viragens à esquerda em vias movimentadas e inversões de marcha.
  - A heurística é a distância em linha reta vezes o custo mínimo por metro, o que a mantém admissível. Os multiplicadores de bónus têm um limite mínimo.
  - Os testes comparam o A* com o Dijkstra em grafos aleatórios.
- **Custo de uma aresta** = tempo (modelo físico) × fator de conforto × fator do motivo + penalização de viragem. Valores iniciais, a calibrar com as rotas de referência:
  - SEGREGADA ×0,75; FAIXA ×0,9; PARTILHADA_30 ×0,95; CICLOPEDONAL ×0,9 em lazer e ×1,2 em desporto; TRILHO depende do tipo de bicicleta
  - via principal sem infraestrutura ×1,6 a ×2,5; carris de elétrico ×1,5; calçada ×1,3 a ×2 (preferência)
  - declive acima do máximo escolhido: penalização forte; desmontar: velocidade a pé e ×4; escadas: excluídas
- **ETA físico** (essencial nas colinas):
  - Potência conforme o nível do ciclista (descontraído, médio ou desportivo) e o tipo de bicicleta (cidade, estrada, BTT/gravel, e-bike/GIRA com assistência até 25 km/h). Resistência ao rolamento conforme o piso, massa, CdA e declive.
  - Velocidade máxima a descer reduzida em calçada, elétrico e ciclopedonal. Atraso em semáforos.
  - Fator pessoal calibrado com as voltas feitas.
- **Alternativas**: método de penalização (penalizar as arestas já usadas e voltar a calcular). Ficam no máximo 3 rotas, distintas pelo índice de Jaccard.
- **Instruções pt-PT**: "Vire à esquerda para a Rua X", "Entre na ciclovia", "Saia da ciclovia", "Desmonte: escadas", "Subida de 9% nos próximos 200 m".
- **Segurança**: a app **nunca diz que uma rota é "segura"**. Mostra o stress, a % por tipo de infraestrutura e os perigos (carris de elétrico, calçada, descidas íngremes, troços sem infraestrutura com stress 4), com um aviso discreto.

### Descobrir (`DiscoveryEngine`)
- **Entradas**:
  - partida (GPS ou ponto escolhido)
  - chegada opcional: volta circular ou A→B com tempo disponível
  - duração, motivo e restrições: evitar calçada, evitar elétricos, inclinação máxima, só segregada quando possível
  - tipo de bicicleta e nível do ciclista
- **Passos**:
  1. Calcular a distância-alvo a partir do tempo, usando o modelo de ETA, e refiná-la iterativamente.
  2. Gerar 12 a 30 candidatas, com um orçamento de cerca de 1–2 s no dispositivo:
     - **com âncoras**: POIs adequados ao motivo dentro da elipse alcançável, em combinações de 1 a 3 por beam search, com penalização de reutilização de arestas para evitar ida e volta pelo mesmo caminho
     - **voltas geométricas**: 2 ou 3 pontos virtuais em polígono, com rumo e semente variáveis
  3. Pontuar cada candidata por:
     - desvio face ao tempo pedido
     - % de infraestrutura e stress
     - pontuação cénica: km junto ao rio ou em parques e âncoras visitadas
     - perfil de elevação ajustado ao motivo
     - repetição de troços e perigos
  4. Mostrar as 3 mais diversas, com chips do tipo "porquê esta rota" (ex.: "68% em ciclovia segregada · 2 miradouros · +210 m · 47 min").
  5. Botão "Outra rota" (nova semente) e ajustes rápidos ("mais rio", "menos subidas").
- **Descidas**: dá prioridade a A→B que acabem mais abaixo (ex.: Graça → rio), a subidas em declive suave e a descidas em asfalto ou ciclovia. Evita descidas íngremes em calçada ou com carris de elétrico.

## App Android — UX

- **Ecrã principal**:
  - mapa claro centrado em Lisboa, com a rede ciclável colorida por classe e uma legenda
  - barra "Para onde vais?" e um FAB grande "Descobrir"
  - camadas opcionais: POIs, GIRA, bebedouros, estacionamento
  - tocar num troço da CML mostra a designação e a tipologia
- **Rota**:
  - bottom sheet com cartões (tempo, distância, D+/D−, % por infraestrutura, stress)
  - linha da rota colorida por infraestrutura, declive ou stress (alternável)
  - perfil de elevação em Compose Canvas, com os perigos marcados
  - folha de preferências
- **Seguir rota (v1)**:
  - posição Fused a 1 Hz, com snapping à rota
  - próxima manobra em letra grande e "o que vem aí" (subida de X% em Y m)
  - fora da rota (mais de 40 m durante mais de 5 s) → recálculo local imediato
  - ecrã sempre ligado e resumo no fim
  - voz e serviço em primeiro plano ficam para mais tarde
- **Guardados**: favoritos (Casa/Trabalho), histórico de pesquisas e rotas guardadas (Room), exportação e importação de GPX.
- **Definições**: tipo de bicicleta, nível, preferências por omissão e "Dados atualizados em…".
- **Sobre**: atribuições e licenças (AboutLibraries).
- **Geral**: pt-PT com strings externalizadas, modo escuro e acessibilidade (alvos grandes, contraste). A localização é processada só no dispositivo.

## Fases

Cada fase termina com build, testes, instalação e execução no emulador e uma verificação visual por screenshot, conforme o CLAUDE.md.

0. **Ambiente e esqueleto**
   - Instalar via winget o JDK 21 e o Android Studio, depois o SDK, o emulador e uma imagem x86_64 através do `sdkmanager`. **A aceitação das licenças do SDK fica contigo.** Se o emulador pedir a Windows Hypervisor Platform, és tu que a ativas.
   - `git init` e `.gitignore`.
   - Build multi-módulo e version catalog. App vazia com Hilt, Compose M3 e o tema LisBora a correr no emulador. `CLAUDE.md` passa para a raiz.
1. **Mapa e ciclovias**
   - `pipeline fetch-cml`: paginação, normalização em classes e `ciclovias.geojson`.
   - Extrato PMTiles e estilo.
   - MapLibre com o mapa base offline, a camada de ciclovias com legenda e informação ao toque, a localização do utilizador e as atribuições.
   - Primeiro passo de verificação: confirmar `pmtiles://file://` no emulador. Se falhar, uso MBTiles ou OpenFreeMap online como recurso temporário.
2. **Grafo e router (JVM)**
   - `pipeline build`: importação com GraphHopper, elevação, exportação, conflação, atributos e relatório QA.
   - `:core:routing` com o A* sobre arestas, a pesagem por omissão e o ETA.
   - Testes unitários e **rotas de referência no grafo real**, entre elas:
     - Cais do Sodré → Belém usa 80% ou mais de ciclovia ribeirinha
     - Marquês → Saldanha usa a pista da Duque de Ávila / Fontes Pereira de Melo
     - nunca usa a Ponte 25 de Abril nem escadas
     - Baixa → Graça com inclinação máxima de 8% evita a Calçada da Glória
3. **Planear A→B**
   - Pacote de dados embutido e `RoutingRepository` (`Dispatchers.Default`).
   - Pesquisa offline e pressão longa no mapa.
   - Até 3 alternativas, cartões, perfil de elevação, perigos e preferências (DataStore).
   - Favoritos e histórico (Room).
4. **Descobrir**
   - UI (duração, motivo, circular ou A→B), `DiscoveryEngine`, chips explicativos, "Outra rota" e guardar.
   - Camada de POIs.
5. **Seguir rota**
   - Modo navegação, instruções pt-PT, recálculo e resumo.
   - GPX e calibração pessoal do ETA.
6. **Atualização de dados sem nova versão da app**
   - `manifest.json` e pacotes versionados (GitHub Releases num repositório público, a decidir nessa altura).
   - Atualização por WorkManager com sha256, troca atómica e reversão.
   - GitHub Actions semanal: corre o pipeline e os testes de qualidade e publica só se houver mudanças e o QA passar.
7. **Polimento**
   - Voz (TTS) e serviço em primeiro plano com o ecrã desligado.
   - Desempenho (perfil de arranque, tempo de rota), acessibilidade e preparação para a Play Store (política de privacidade).

Para o futuro, opcional:
- Supabase (sincronização e partilha)
- correções de contrassentido com lista manual
- disponibilidade GIRA em tempo real, se houver API aberta
- "pôr do sol nos miradouros"
- relatórios de perigos

## Verificação

- `gradlew :core:routing:test :pipeline:test`: testes de grafos sintéticos (sentido único, acessos, restrições, A* = Dijkstra), escrita e leitura do formato, conflação em fixtures (pista paralela à estrada → só a pista fica marcada) e ETA.
- `gradlew :pipeline:run --args="build"`: gera o `build/datapack/` e `qa/report.md` com a cobertura da CML e o GeoJSON das falhas, para ver no geojson.io ou no QGIS.
- `gradlew :pipeline:goldenRoutes`: as rotas de referência têm de passar. O mesmo teste corre no CI antes de cada publicação.
- `gradlew :app:testDebugUnitTest`: ViewModels com Turbine e repositórios falsos.
- `gradlew :app:connectedDebugAndroidTest`: testes de UI em Compose para pesquisa → rota → seguir e para Descobrir, mais os DAOs do Room.
- No emulador: `assembleDebug`, `adb install`, `am start` e screenshot. O GPS é simulado ao longo da rota (script com `adb emu geo fix` ou reprodução de GPX) para testar o snapping, o fora-da-rota e o recálculo.
- Revisão manual por fase: as ciclovias desenhadas coincidem com as usadas pelo router (mesmo `dataVersion`), e as rotas e os perfis de elevação fazem sentido nos bairros com colinas.

## Licenças e atribuições

- **OSM (ODbL)**: "© OpenStreetMap contributors" no mapa. O grafo e o índice de pesquisa distribuídos são bases de dados derivadas ODbL, e isso tem de ficar indicado.
- **Rede Ciclável © Câmara Municipal de Lisboa**, CC BY 4.0, com a indicação de que os dados foram modificados (normalizados e conflacionados). **Não se importam dados da CML para o OSM** sem autorização explícita.
- **Protomaps** (dados OSM) e fontes OFL. **Elevação**: atribuição conforme a fonte (por exemplo, Copernicus), a confirmar na Fase 2.
- MapLibre (BSD-2) e restantes bibliotecas no ecrã de licenças. O GraphHopper (Apache 2.0) só corre no pipeline.
- **Sem segredos**: a app não usa chaves de API. Qualquer configuração do CI fica em GitHub Secrets.

## Riscos e mitigação

- **Correção do router próprio**: o parsing de OSM fica a cargo do GraphHopper, há testes de propriedades e rotas de referência no CI.
- **Desalinhamento entre a CML e o OSM**: relatório QA, `overrides.geojson` e atribuição só à aresta mais próxima.
- **Ruído na elevação** em pontes, viadutos e túneis: suavização, interpolação e validação em subidas conhecidas.
- **PMTiles locais ou Vulkan no emulador**: verificação logo na Fase 1, com o artefacto OpenGL e MBTiles como plano B.
- **Tamanho do pacote** (estimativa: 60 a 100 MB com o mapa base): região configurável e atualizações só dos ficheiros que mudaram.
