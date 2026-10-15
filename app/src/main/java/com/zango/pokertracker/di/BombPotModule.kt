package com.zango.pokertracker.di

import com.zango.pokertracker.bombpot.BombPotVibration
import com.zango.pokertracker.bombpot.SystemBombPotVibration
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class BombPotModule {

    @Binds
    abstract fun bindBombPotVibration(impl: SystemBombPotVibration): BombPotVibration
}
