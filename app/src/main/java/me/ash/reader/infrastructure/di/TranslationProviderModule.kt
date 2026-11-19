package me.ash.reader.infrastructure.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import me.ash.reader.infrastructure.translation.HttpJsonTranslationProvider
import me.ash.reader.infrastructure.translation.MlKitTranslationProvider
import me.ash.reader.infrastructure.translation.TranslationProvider

@Module
@InstallIn(SingletonComponent::class)
abstract class TranslationProviderModule {
    @Binds
    @IntoSet
    abstract fun bindOnDeviceProvider(provider: MlKitTranslationProvider): TranslationProvider

    @Binds
    @IntoSet
    abstract fun bindHttpJsonProvider(provider: HttpJsonTranslationProvider): TranslationProvider
}
