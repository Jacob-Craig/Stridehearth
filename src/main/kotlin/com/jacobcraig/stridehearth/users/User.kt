package com.jacobcraig.stridehearth.users

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "app_user")
class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(nullable = false, unique = true)
    var email: String,
    @Column(name = "password_hash", nullable = false)
    var passwordHash: String,
    @Column(nullable = false, unique = true)
    var username: String,
    @Column(name = "first_name")
    var firstName: String? = null,
    @Column(name = "last_name")
    var lastName: String? = null,
    /** Whether this is the Server's Administrator: its first account, the one allowed to issue Invites. */
    @Column(nullable = false)
    var administrator: Boolean = false,
    /** The athlete's IANA time zone, which their local dates are counted in; null until set, read as UTC (ADR-0015). */
    @Column(name = "time_zone", length = 64)
    var timeZone: String? = null,
)
