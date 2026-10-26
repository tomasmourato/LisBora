package pt.lisbora.di

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
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
import pt.lisbora.data.discover.DiscoveryRepository
import pt.lisbora.data.discover.GraphDiscoveryRepository
import pt.lisbora.data.gira.BundledGiraRepository
import pt.lisbora.data.gira.GiraRepository
import pt.lisbora.data.gira.LiveGiraRepository
import pt.lisbora.data.gira.VaimooGiraFeed
import pt.lisbora.data.gpx.ContentGpxFiles
import pt.lisbora.data.gpx.GpxFiles
import pt.lisbora.data.location.AndroidLocationRepository
import pt.lisbora.data.location.LocationRepository
import pt.lisbora.data.map.BundledMapDataRepository
import pt.lisbora.data.map.MapDataRepository
import pt.lisbora.data.routing.GraphRoutingRepository
import pt.lisbora.data.routing.RoutingRepository
import pt.lisbora.data.saved.RoomSavedPlacesRepository
import pt.lisbora.data.saved.RoomSavedRoutesRepository
import pt.lisbora.data.saved.SavedPlacesDatabase
import pt.lisbora.data.saved.SavedPlacesRepository
import pt.lisbora.data.saved.SavedRoutesRepository
import pt.lisbora.data.search.OfflineSearchRepository
import pt.lisbora.data.search.SearchRepository
import pt.lisbora.data.settings.DataStoreRidingSettingsRepository
import pt.lisbora.data.settings.RidingSettingsRepository
import java.io.File
import java.time.Clock
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** For CPU-bound work: route searches, loading the graph and the search index. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

private val Context.ridingSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "riding_settings")

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

    @Provides
    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    fun ridingSettingsStore(@ApplicationContext context: Context): DataStore<Preferences> = context.ridingSettingsStore

    @Provides
    @Singleton
    fun savedPlacesDatabase(@ApplicationContext context: Context): SavedPlacesDatabase =
        Room.databaseBuilder(context, SavedPlacesDatabase::class.java, SavedPlacesDatabase.NAME).build()

    @Provides
    fun clock(): Clock = Clock.systemUTC()

    /** Live bikes and docks, with the bundled stations when offline. */
    @Provides
    @Singleton
    fun giraRepository(bundled: BundledGiraRepository, feed: VaimooGiraFeed, clock: Clock): GiraRepository =
        LiveGiraRepository(bundled, feed, clock) { Log.w("LisBoraGira", it) }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun mapDataRepository(impl: BundledMapDataRepository): MapDataRepository

    @Binds
    abstract fun routingRepository(impl: GraphRoutingRepository): RoutingRepository

    @Binds
    abstract fun searchRepository(impl: OfflineSearchRepository): SearchRepository

    @Binds
    abstract fun locationRepository(impl: AndroidLocationRepository): LocationRepository

    @Binds
    abstract fun ridingSettingsRepository(impl: DataStoreRidingSettingsRepository): RidingSettingsRepository

    @Binds
    abstract fun savedPlacesRepository(impl: RoomSavedPlacesRepository): SavedPlacesRepository

    @Binds
    abstract fun savedRoutesRepository(impl: RoomSavedRoutesRepository): SavedRoutesRepository

    @Binds
    abstract fun discoveryRepository(impl: GraphDiscoveryRepository): DiscoveryRepository

    @Binds
    abstract fun gpxFiles(impl: ContentGpxFiles): GpxFiles
}
