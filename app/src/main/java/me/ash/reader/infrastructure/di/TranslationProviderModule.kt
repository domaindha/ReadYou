package me.ash.reader.infrastructure.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import me.ash.reader.infrastructure.translation.AzureTranslatorProvider
import me.ash.reader.infrastructure.translation.HttpJsonTranslationProvider
import me.ash.reader.infrastructure.translation.MyMemoryTranslationProvider
import me.ash.reader.infrastructure.translation.LibreTranslateProvider
import me.ash.reader.infrastructure.translation.LingvaTranslationProvider
import me.ash.reader.infrastructure.translation.MlKitTranslationProvider
import me.ash.reader.infrastructure.translation.TranslationLanguageDetector
import me.ash.reader.infrastructure.translation.TranslationProvider
import me.ash.reader.infrastructure.translation.AndroidTranslationNetworkPolicy
import me.ash.reader.infrastructure.translation.TranslationNetworkPolicy

@Module
@InstallIn(SingletonComponent::class)
abstract class TranslationProviderModule {
    @Binds
    abstract fun bindTranslationNetworkPolicy(
        policy: AndroidTranslationNetworkPolicy,
    ): TranslationNetworkPolicy

    @Binds
    abstract fun bindTranslationLanguageDetector(
        provider: MlKitTranslationProvider,
    ): TranslationLanguageDetector

    @Binds
    @IntoSet
    abstract fun bindOnDeviceProvider(provider: MlKitTranslationProvider): TranslationProvider

    @Binds
    @IntoSet
    abstract fun bindHttpJsonProvider(provider: HttpJsonTranslationProvider): TranslationProvider

    @Binds
    @IntoSet
    abstract fun bindAzureTranslatorProvider(provider: AzureTranslatorProvider): TranslationProvider

    @Binds
    @IntoSet
    abstract fun bindMyMemoryProvider(provider: MyMemoryTranslationProvider): TranslationProvider

    @Binds
    @IntoSet
    abstract fun bindLibreTranslateProvider(provider: LibreTranslateProvider): TranslationProvider

    @Binds
    @IntoSet
    abstract fun bindLingvaProvider(provider: LingvaTranslationProvider): TranslationProvider
}
