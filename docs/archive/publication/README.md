# Orkestra — Documents

Documentação e propostas de contratos da biblioteca pública Orkestra para Java 25.

Destino escolhido: organização `Orkestra-java`, repositório público `Documents`. A organização e o repositório ainda não foram criados por este trabalho.

- [Especificação vigente](Orkestra.md)
- [Protótipo de API Java](design/api/README.md)
- [Exemplo de composição tipada](design/api/OrderExample.java)
- [Documento anterior preservado](Orkestra.reference.md)

O núcleo executável fica em `../orkestra-core/`, acompanhado de adaptadores
opcionais para logs, OpenTelemetry e Spring. Consulte [../README.md](../README.md)
para build e exemplos e [validação](../docs/VALIDATION.md) para resultados.
Este diretório é um pacote de documentação, sem build próprio. Os contratos
em `design/api/` são um protótipo histórico separado da implementação atual.
