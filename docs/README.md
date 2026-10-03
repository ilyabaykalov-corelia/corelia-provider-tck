# Документация provider TCK

TCK предназначен для проверки соблюдения контракта, а не для запуска
приложения. Запускайте его из корня Maven reactor с зависимостями модуля.
Если implementation поддерживает optional capability, тестируйте только
заявленную `ProviderDescriptor` семантику и явно проверяйте expected failures
для не поддерживаемых операций.

См. [provider SPI](../../docs/provider-spi.md) и [общие проверки](../../docs/testing.md).
