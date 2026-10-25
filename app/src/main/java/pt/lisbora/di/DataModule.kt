package pt.lisbora.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import pt.lisbora.data.datapack.AssetSource
import pt.lisbora.data.datapack.DataPackInstaller
import pt.lisbora.data.map.BundledMapDataRepository
import pt.lisbora.data.map.MapDataRepository
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    fun assetSource(@ApplicationContext context: Context): AssetSource = AssetSource { context.assets.open(it) }

    @Provides
    @Singleton
    fun dataPackInstaller(assets: AssetSource, @ApplicationContext context: Context): DataPackInstaller =
        DataPackInstaller(assets, File(context.filesDir, "datapack"))

    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun mapDataRepository(impl: BundledMapDataRepository): MapDataRepository
}
