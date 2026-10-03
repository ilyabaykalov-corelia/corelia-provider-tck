# corelia-provider-tck

Набор повторно используемых контрактных проверок provider SPI. Это не runtime
сервис и не реализация provider: его используют authors адаптеров и системные
тесты для проверки provider-neutral семантики.

TCK дополняет, но не заменяет интеграционные проверки выбранного хранилища,
BPMN engine или IAM. В частности, in-memory fixture не доказывает корректность
S3 или Flowable в развёртывании.

```bash
mvn -pl corelia-provider-tck -am test
```

Перед добавлением новой реализации проверьте [SPI-контракт](../corelia-provider-spi/README.md)
и добавьте необходимые fixtures/contract cases, не привязанные к SDK provider.
