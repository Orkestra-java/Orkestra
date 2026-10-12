<div align="center">

# Orkestra

**Componha tarefas. Controle a execução. Preserve os tipos.**

Biblioteca Java para coordenar fluxos dentro da sua aplicação.

![Java 25](https://img.shields.io/badge/Java-25-ED8B00?style=flat-square)
![Maven](https://img.shields.io/badge/build-Maven-1565C0?style=flat-square)
![Version](https://img.shields.io/badge/version-0.1.0--SNAPSHOT-607D8B?style=flat-square)
[![License: Apache 2.0](https://img.shields.io/badge/license-Apache--2.0-4CAF50?style=flat-square)](LICENSE)

[Começar](#começar) · [Recursos](#recursos) · [Exemplos](#exemplos) · [Desenvolvimento](#desenvolvimento) · [Documentação](#documentação)

</div>

Orkestra transforma tarefas Java em **composições tipadas, imutáveis e reutilizáveis**.
Cada etapa recebe a saída da anterior; o executor coordena paralelismo, limites,
retry, cache e compensação. Você define as operações de negócio e controla o estado
da aplicação.

O núcleo é um único artefato, **`io.orkestra:orkestra-core`**, sem dependências
externas em produção. Requer **JDK 25** e funciona no classpath ou com JPMS.

> **Estado do projeto:** versão inicial `0.1.0-SNAPSHOT`, ainda sem publicação
> no Maven Central. A API pode mudar antes da primeira release. Para experimentar,
> instale o artefato no seu repositório Maven local.

## Como funciona

```mermaid
flowchart LR
    I[Entrada] --> V[Validar pedido]
    V --> P[Preparar pedido]
    P --> R[Reservar estoque]
    R --> C[Cobrar pagamento]
    C --> F[Confirmar pedido]
    F --> O[Execution com resultado e ID]
```

Uma `Task<C, I, O>` recebe contexto `C` e entrada `I`, e produz saída `O`.
Uma `Composition<C, I, O>` combina essas operações. `Orchestrator.perform(...)`
executa a definição e retorna `Execution<O>` quando o fluxo termina com sucesso.
Falhas são lançadas como exceções, com diagnóstico recuperável por `Failures.details(...)`.

## Começar

### 1. Instale a biblioteca localmente

Com JDK 25, na raiz deste checkout:

```sh
cd java
./mvnw install
```

No Windows, use `mvnw.cmd`. O wrapper fornece Maven 3.9.16; o primeiro build
precisa de rede para baixar Maven, plugins e dependências de teste.

### 2. Adicione a dependência ao seu projeto

Configure seu projeto consumidor para Java 25 e adicione ao `pom.xml`:

```xml
<dependency>
    <groupId>io.orkestra</groupId>
    <artifactId>orkestra-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

<details>
<summary>Usando módulos Java (JPMS)</summary>

Adicione ao `module-info.java` da aplicação:

```java
requires io.orkestra.core;
```

O núcleo exige apenas `java.base`. Logging, OpenTelemetry e Spring são
integrações opcionais em artefatos separados.

</details>

### 3. Execute sua primeira composição

```java
import io.orkestra.core.Orchestrator;
import io.orkestra.core.composition.Compositions;

public class Main {
    public static void main(String[] args) throws Exception {
        var composition = Compositions.<Void, Integer>create()
                .named("calculate-total")
                .perform((context, input) -> input * 2)
                .perform((context, total) -> "Total: " + total)
                .build();

        try (var orchestrator = new Orchestrator()) {
            var execution = orchestrator.perform(composition, null, 7);
            System.out.println(execution.output());
        }
    }
}
```

```text
Total: 14
```

Neste exemplo, a primeira tarefa produz `Integer` e a seguinte produz `String`.
O tipo da saída acompanha a composição. `Void` indica que esse fluxo não precisa
de contexto. O executor cria seus recursos locais automaticamente.

## Recursos

| Recurso | API | Aplicação |
| --- | --- | --- |
| Sequência e reutilização | `perform`, `Composition` | Encadear tarefas ou composições existentes |
| Paralelismo tipado | `TaskRef`, `parallel`, `join` | Executar ramos e combinar seus resultados |
| Decisões e coleções | `situation`, `forEach`, `require` | Escolher caminhos, processar itens e validar entradas |
| Prazos e capacidade | `within`, `concurrently`, `isolate`, `exclusively` | Limitar duração, concorrência e acesso por chave |
| Retry e circuit breaker | `retry`, `guard` | Repetir falhas selecionadas e proteger operações |
| Cache local | `cache`, `MemoryCache` | Reutilizar resultados com TTL, limite e invalidação |
| Compensação | `withCompensation`, `compensateWith` | Registrar ações para desfazer efeitos após uma falha |
| Diagnóstico e histórico | `onFailure`, `Failures`, `MemoryHistory` | Traduzir falhas e acompanhar execuções |
| Ciclo de vida | `AutoCloseable`, `shutdown` | Encerrar execuções com orçamento e relatório de pendências |

### Paralelismo com resultados tipados

Os ramos recebem a mesma entrada. `join` combina os resultados, acessados pelas
referências das tarefas; ele só executa quando todos os ramos terminam com sucesso.

```mermaid
flowchart LR
    I[Entrada: 7] --> D[Duplicar]
    I --> T[Triplicar]
    D --> DR[Resultado: 14]
    T --> TR[Resultado: 21]
    DR --> J[join]
    TR --> J
    J --> O[Totals: 14 e 21]
```

```java
import io.orkestra.core.composition.Compositions;
import io.orkestra.core.composition.TaskRef;

record Totals(int doubled, int tripled) {}

var doubleValue = TaskRef.<Void, Integer, Integer>of((context, input) -> input * 2);
var tripleValue = TaskRef.<Void, Integer, Integer>of((context, input) -> input * 3);

var composition = Compositions.<Void, Integer>create()
        .parallel(group -> group.concurrently(2)
                .perform(doubleValue)
                .perform(tripleValue))
        .join((context, results) -> new Totals(
                results.get(doubleValue), results.get(tripleValue)))
        .build();
```

Use `orchestrator.perform(composition, null, 7)` para obter os totais do diagrama.

### Compensação de efeitos

Quando uma etapa falha, as ações de compensação das etapas concluídas são
executadas em ordem inversa de conclusão. No fluxo sequencial abaixo, uma falha
na confirmação dispara o estorno e, depois, a liberação da reserva.

```mermaid
sequenceDiagram
    participant O as Orchestrator
    participant E as Estoque
    participant P as Pagamento
    participant C as Confirmação
    O->>E: Reservar
    E-->>O: Reserva concluída
    O->>P: Cobrar
    P-->>O: Cobrança concluída
    O->>C: Confirmar
    C-->>O: Falha
    O->>P: Estornar
    P-->>O: Estorno concluído
    O->>E: Liberar reserva
    E-->>O: Reserva liberada
    O-->>O: Traduzir falha com onFailure
```

Compensação depende de ações explícitas fornecidas pela aplicação. Ela não é uma
transação de banco de dados. Veja o [exemplo completo](examples/WorkflowExample.java),
que registra reserva, cobrança, estorno e liberação.

### Contratos de execução

- `perform(...)` aguarda o resultado; o executor usa virtual threads internamente.
- Composições e políticas são imutáveis. Tarefas, contexto e objetos compartilhados
  precisam suportar a concorrência que a aplicação configura.
- Retry exige um predicado explícito; `upTo(2)` significa **duas tentativas totais**.
- Cache e retry se aplicam a tarefas individuais. Cache armazena somente sucessos.
- `onFailure` traduz a falha uma vez; não retoma o fluxo como sucesso.
- Cancelamento é cooperativo. Trabalho que ignora interrupção pode permanecer
  pendente e impedir compensação segura; diagnóstico e shutdown reportam isso.

O escopo é execução dentro do processo. Persistência durável, retomada após
reinício e coordenação distribuída não fazem parte deste núcleo.
A [especificação](docs/specification.md) detalha os contratos e limites.

## Integrações opcionais

```mermaid
flowchart TB
    A[Aplicação Java] --> C[orkestra-core]
    C --> J[JDK 25]
    L[orkestra-observability] --> C
    O[orkestra-opentelemetry] --> C
    S[orkestra-spring] --> C
```

| Artefato (`io.orkestra`) | Integração |
| --- | --- |
| `orkestra-core` | Composições, execução e recursos locais |
| `orkestra-observability` | Eventos em JSON usando `java.util.logging` |
| `orkestra-opentelemetry` | Spans usando um `Tracer` configurado pela aplicação |
| `orkestra-spring` | Encerramento coordenado por `SmartLifecycle` |

Os adaptadores usam a mesma versão do núcleo e são adicionados explicitamente
pelo consumidor. Configuração de exporters, SDKs e recursos externos pertence
à aplicação. Consulte o [guia de integrações](docs/EXTENSIONS.md).

## Exemplos

Na raiz do checkout, depois de executar o build:

```sh
scripts/run-examples.sh
```

| Exemplo | Demonstração |
| --- | --- |
| [PlaceOrder.java](examples/PlaceOrder.java) | Encadeamento tipado, composição reutilizada e tradução de falhas |
| [WorkflowExample.java](examples/WorkflowExample.java) | Paralelismo, decisões, retry, cache, histórico e compensação |

<details>
<summary>Saída do exemplo completo</summary>

```text
Confirmed: customer-123
Actions: [reserve, charge, confirm]
Prices: [2, 4, 6]
History events: 43
Failure: TASK_FAILED
Compensated: [reserve, charge, refund, release-reservation]
```

O exemplo executa um pedido aceito e outro cuja confirmação falha.
A segunda execução verifica a ordem das ações de compensação.

</details>

## Desenvolvimento

Todos os comandos abaixo partem da raiz do repositório:

| Comando | Resultado |
| --- | --- |
| `(cd java && ./mvnw test)` | Executa os testes do núcleo |
| `(cd java && ./mvnw verify)` | Testa e empacota `orkestra-core` |
| `(cd java && ./mvnw -Padapters verify)` | Inclui testes e JARs das três integrações |
| `(cd java && ./mvnw -Padapters install)` | Instala núcleo e adaptadores no Maven local |
| `python3 scripts/check-api.py` | Compara as assinaturas do núcleo com o baseline |
| `python3 scripts/check-api.py --all` | Verifica também os adaptadores, após seu build |

A validação local registrou **74 testes do núcleo** e **77 com os adaptadores**,
sem falhas. Consulte [validação](docs/VALIDATION.md) para as verificações executadas
e os limites de validação de CI, release e benchmarks.

<details>
<summary>Estrutura do repositório</summary>

```text
.
├── java/
│   ├── core/                  # Biblioteca principal: fontes e testes
│   ├── adapters/              # Logging, OpenTelemetry e Spring
│   ├── benchmarks/            # Medições opcionais
│   ├── pom.xml                # Build Maven
│   └── mvnw                   # Maven Wrapper
├── examples/                  # Consumidores executáveis
├── docs/                      # Especificação, guias e decisões
├── api/                       # Baseline das assinaturas públicas
├── scripts/                   # Ferramentas de validação
└── .github/workflows/          # Build e benchmarks
```

O núcleo usa pacotes por responsabilidade em `io.orkestra.core`.
Veja a [organização da biblioteca](docs/ARCHITECTURE.md).

</details>

## Documentação

- [Especificação](docs/specification.md) — semântica da API e contratos de execução.
- [Integrações](docs/EXTENSIONS.md) — provedores, eventos, telemetria e Spring.
- [Validação](docs/VALIDATION.md) — evidências de testes e empacotamento.
- [Preparação de release](docs/RELEASE.md) — fontes, Javadoc e compatibilidade.
- [Benchmarks](java/benchmarks/README.md) — ferramentas e interpretação das medições.
- [Guia de contribuição](AGENTS.md) — organização, estilo e verificações.

## Contribuição e licença

Antes de alterar contratos públicos, atualize a especificação e os testes
correspondentes. Descreva o comportamento alterado, a motivação e a validação
no pull request. Mudanças no baseline de API devem ser revisadas explicitamente.

Orkestra é distribuída sob a [licença Apache-2.0](LICENSE).
