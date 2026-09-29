Package: libacl1
Source: acl (2.3.2-2)
Version: 2.3.2-2+b1
Installed-Size: 102
Maintainer: Guillem Jover <guillem@debian.org>
Architecture: arm64
Depends: libc6 (>= 2.38)
Description: access control list - shared library
Multi-Arch: same
Homepage: https://savannah.nongnu.org/projects/acl/
Description-md5: 819a4a0833dcbd5db23e4a5fd341c3aa
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/a/acl/libacl1_2.3.2-2+b1_arm64.deb
Size: 32216
MD5sum: cda23fa6ffee4760f7d58f926761f079
SHA256: 10b2399169e42e6d8db55944116cb2ed61caab997cf700acb83de0b0668e5721

Package: apt
Version: 3.0.3
Installed-Size: 4912
Maintainer: APT Development Team <deity@lists.debian.org>
Architecture: arm64
Replaces: apt-transport-https (<< 1.5~alpha4~), apt-utils (<< 1.3~exp2~)
Provides: apt-transport-https (= 3.0.3)
Depends: base-passwd (>= 3.6.1) | adduser, sqv (>= 1.3.0), libapt-pkg7.0 (>= 3.0.3), debian-archive-keyring, libc6 (>= 2.38), libgcc-s1 (>= 3.0), libseccomp2 (>= 2.4.2), libssl3t64 (>= 3.0.0), libstdc++6 (>= 13.1), libsystemd0
Recommends: ca-certificates
Suggests: apt-doc, aptitude | synaptic | wajig, dpkg-dev (>= 1.17.2), gnupg | gnupg2 | gnupg1, powermgmt-base
Conflicts: apt-verify, libnettle8 (<< 3.9.1-2.2~)
Breaks: apt-transport-https (<< 1.5~alpha4~), apt-utils (<< 1.3~exp2~), aptitude (<< 0.8.10)
Description: commandline package manager
Description-md5: 6b534aaa75feaadaa911a5a6e81b66b2
Tag: admin::package-management, devel::lang:ruby, hardware::storage,
 hardware::storage:cd, implemented-in::c++, implemented-in::perl,
 implemented-in::ruby, interface::commandline, network::client,
 protocol::ftp, protocol::http, protocol::ipv6, role::program,
 scope::application, scope::utility, suite::debian, use::downloading,
 use::organizing, use::playing, use::searching, works-with-format::html,
 works-with::audio, works-with::software:package, works-with::text
Section: admin
Priority: required
Filename: pool/main/a/apt/apt_3.0.3_arm64.deb
Size: 1361960
MD5sum: 5371ac3167816a5489f7cc3c6b2e1572
SHA256: 9d88b95c6a6c9880b1bb40aebe7df3b34a0a160a9430d76fac496512a4cf69a6

Package: libapt-pkg7.0
Source: apt
Version: 3.0.3
Installed-Size: 3643
Maintainer: APT Development Team <deity@lists.debian.org>
Architecture: arm64
Provides: libapt-pkg (= 3.0.3)
Depends: libbz2-1.0, libc6 (>= 2.38), libgcc-s1 (>= 3.0), liblz4-1 (>= 0.0~r127), liblzma5 (>= 5.1.1alpha+20120614), libssl3t64 (>= 3.0.0), libstdc++6 (>= 14), libsystemd0 (>= 221), libudev1 (>= 183), libxxhash0 (>= 0.8), libzstd1 (>= 1.5.5), zlib1g (>= 1:1.2.2.3)
Recommends: apt (>= 3.0.3)
Conflicts: libnettle8 (<< 3.9.1-2.2~)
Breaks: appstream (<< 0.9.0-3~), apt (<< 1.6~), aptitude (<< 0.8.9), dpkg (<< 1.20.8), libapt-inst1.5 (<< 0.9.9~)
Description: package management runtime library
Multi-Arch: same
Description-md5: 1e225d7f6cafc33f74a452fd5c53755e
Section: libs
Priority: optional
Filename: pool/main/a/apt/libapt-pkg7.0_3.0.3_arm64.deb
Size: 978112
MD5sum: 4a0492db883585ee6729796b4c4a6e64
SHA256: c5aea30677a9c7a56edc92fcba3361f5e8cb31d5cdfedf72fc9d8e4fba150bda

Package: libattr1
Source: attr
Version: 1:2.5.2-3
Installed-Size: 100
Maintainer: Guillem Jover <guillem@debian.org>
Architecture: arm64
Depends: libc6 (>= 2.17)
Description: extended attribute handling - shared library
Multi-Arch: same
Homepage: https://savannah.nongnu.org/projects/attr/
Description-md5: d4aef101ff5430158eae72e808000f6c
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/a/attr/libattr1_2.5.2-3_arm64.deb
Size: 22664
MD5sum: cb81884bbd25294d8a1404dcacda8a80
SHA256: 25057d7bfba969a76cdf18ae88ce7ff6e664520726ddf02cdde97c03d5e9a43c

Package: libaudit-common
Source: audit
Version: 1:4.0.2-2
Installed-Size: 25
Maintainer: Laurent Bigonville <bigon@debian.org>
Architecture: all
Description: Dynamic library for security auditing - common files
Multi-Arch: foreign
Homepage: https://people.redhat.com/sgrubb/audit/
Description-md5: f1e698fe58902299a06c8f79f079ac9a
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/a/audit/libaudit-common_4.0.2-2_all.deb
Size: 12692
MD5sum: db8824e85e136daba1f3c864224fa159
SHA256: b6edcd7aa36a2a5a1abd2c52966d69a99eebdb69c8f5cb33cbf415b0875775e3

Package: libaudit1
Source: audit (1:4.0.2-2)
Version: 1:4.0.2-2+b2
Installed-Size: 223
Maintainer: Laurent Bigonville <bigon@debian.org>
Architecture: arm64
Depends: libaudit-common (>= 1:4.0.2-2), libc6 (>= 2.38), libcap-ng0 (>= 0.7.9)
Description: Dynamic library for security auditing
Multi-Arch: same
Homepage: https://people.redhat.com/sgrubb/audit/
Description-md5: ec521af3cbcca51c5a26a117f114a9b5
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/a/audit/libaudit1_4.0.2-2+b2_arm64.deb
Size: 54568
MD5sum: 3b768977c80eac2c14276fa395844e9b
SHA256: a1124818a702c584ee139948c20596f6bb464d8110a14bc2dc748646ae461cab

Package: base-files
Version: 13.8+deb13u6
Essential: yes
Installed-Size: 348
Maintainer: Santiago Vila <sanvila@debian.org>
Architecture: arm64
Replaces: base, dpkg (<= 1.15.0), miscutils
Provides: base, usr-is-merged
Pre-Depends: awk
Breaks: debian-security-support (<< 2019.04.25), initscripts (<< 2.88dsf-13.3), sendfile (<< 2.1b.20080616-5.2~)
Description: Debian base system miscellaneous files
Multi-Arch: foreign
Description-md5: 6d16337f57b84c4747f56438355b2395
Tag: admin::configuring, admin::filesystem, admin::install, admin::login,
 role::app-data, suite::debian
Section: admin
Priority: required
Filename: pool/main/b/base-files/base-files_13.8+deb13u6_arm64.deb
Size: 73276
MD5sum: 368a48d4de9186911eeaa1d8453b0cff
SHA256: 5f5a571ec846d7db05d9f825f0388a37bc033267b33cbf85b4eaa838dffcc41b

Package: base-passwd
Version: 3.6.7
Essential: yes
Installed-Size: 300
Maintainer: Shadow package maintainers <pkg-shadow-devel@lists.alioth.debian.org>
Architecture: arm64
Replaces: base
Depends: libc6 (>= 2.34), libdebconfclient0 (>= 0.145), libselinux1 (>= 3.1~)
Recommends: debconf (>= 0.5) | debconf-2.0
Description: Debian base system master password and group files
Multi-Arch: foreign
Description-md5: aad0cc52ee72b2469af5552851e49f03
Tag: admin::configuring, admin::user-management, implemented-in::c,
 interface::commandline, role::app-data, role::program, scope::utility,
 security::authentication, suite::debian, use::login
Section: admin
Priority: required
Filename: pool/main/b/base-passwd/base-passwd_3.6.7_arm64.deb
Size: 53392
MD5sum: 9b35ab2d7a45da0956167a0fc50df61d
SHA256: c5755fd2cbf76961a1230e708b6bfc12c5400b57f3cd8a255675e9aa11d85848

Package: bash
Source: bash (5.2.37-2)
Version: 5.2.37-2+b9
Essential: yes
Installed-Size: 7359
Maintainer: Matthias Klose <doko@debian.org>
Architecture: arm64
Depends: base-files (>= 2.1.12), debianutils (>= 5.6-0.1)
Pre-Depends: libc6 (>= 2.38), libtinfo6 (>= 6)
Recommends: bash-completion
Suggests: bash-doc
Description: GNU Bourne Again SHell
Multi-Arch: foreign
Homepage: http://tiswww.case.edu/php/chet/bash/bashtop.html
Description-md5: 3522aa7b4374048d6450e348a5bb45d9
Tag: admin::TODO, devel::TODO, devel::interpreter, implemented-in::c,
 interface::shell, interface::text-mode, role::program,
 scope::application, suite::gnu, uitoolkit::ncurses
Section: shells
Priority: required
Filename: pool/main/b/bash/bash_5.2.37-2+b9_arm64.deb
Size: 1456660
MD5sum: 9da5bf6fd5608a17ccedd97794837be8
SHA256: f9b0e4eb4ae81b3934a3211d755de26fa03881af3f159eb582437d3e8f55d9eb

Package: libbz2-1.0
Source: bzip2
Version: 1.0.8-6
Installed-Size: 92
Maintainer: Anibal Monsalve Salazar <anibal@debian.org>
Architecture: arm64
Depends: libc6 (>= 2.17)
Description: high-quality block-sorting file compressor library - runtime
Multi-Arch: same
Homepage: https://sourceware.org/bzip2/
Description-md5: 88cb63f2e4d8cedcf07307314fc3c3d3
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/b/bzip2/libbz2-1.0_1.0.8-6_arm64.deb
Size: 37772
MD5sum: 86ba0d08aa1c87b5ee5dc455b72b5c8d
SHA256: 3537fe4fc577a60c8a9568873cd577db7ebc135eb8bb1825f18caf8972d9d2f2

Package: ca-certificates
Version: 20250419
Installed-Size: 390
Maintainer: Julien Cristau <jcristau@debian.org>
Architecture: all
Depends: openssl (>= 1.1.1), debconf (>= 0.5) | debconf-2.0
Enhances: openssl
Breaks: ca-certificates-java (<< 20121112+nmu1)
Description: Common CA certificates
Multi-Arch: foreign
Description-md5: e867d2a359bea1800b5bff209fc65bd1
Tag: protocol::ssl, role::app-data, security::authentication
Section: misc
Priority: standard
Filename: pool/main/c/ca-certificates/ca-certificates_20250419_all.deb
Size: 161704
MD5sum: ea04777e0779a9e36b0a13bac0e61b4b
SHA256: ef590f89563aa4b46c8260d49d1cea0fc1b181d19e8df3782694706adf05c184

Package: libdebconfclient0
Source: cdebconf
Version: 0.280
Installed-Size: 90
Maintainer: Debian Install System Team <debian-boot@lists.debian.org>
Architecture: arm64
Depends: libc6 (>= 2.38)
Description: Debian Configuration Management System (C-implementation library)
Multi-Arch: same
Description-md5: b6846d950dc3d5d61a275932cc66b18d
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/c/cdebconf/libdebconfclient0_0.280_arm64.deb
Size: 10784
MD5sum: c650e0f0d50d2cf4dc6e70212efa1cc1
SHA256: 8c2976dc62d0f5133018ea62636105335f9b17da77adce7fed1ffb24e2533dba

Package: coreutils
Version: 9.7-3
Essential: yes
Installed-Size: 21046
Maintainer: Michael Stone <mstone@debian.org>
Architecture: arm64
Pre-Depends: libacl1 (>= 2.2.23), libattr1 (>= 1:2.4.48), libc6 (>= 2.38), libcap2 (>= 1:2.10), libgmp10 (>= 2:6.3.0+dfsg), libselinux1 (>= 3.1~), libssl3t64 (>= 3.0.0), libsystemd0 (>= 254)
Description: GNU core utilities
Multi-Arch: foreign
Homepage: https://gnu.org/software/coreutils
Description-md5: d0d975dec3625409d24be1238cede238
Tag: admin::configuring, implemented-in::c, interface::commandline,
 role::program, scope::utility, suite::gnu, works-with::file
Section: utils
Priority: required
Filename: pool/main/c/coreutils/coreutils_9.7-3_arm64.deb
Size: 2948764
MD5sum: 88659d3c1264b01686c9c4391df6b073
SHA256: 99b2531e8d16e4f2d3f239ae3cb57913ec1f43764d2d6cd5c69154cbb1e57f3e

Package: dash
Version: 0.5.12-12
Essential: yes
Installed-Size: 275
Maintainer: Andrej Shadura <andrewsh@debian.org>
Architecture: arm64
Depends: debianutils (>= 5.6-0.1)
Pre-Depends: libc6 (>= 2.38)
Description: POSIX-compliant shell
Multi-Arch: foreign
Homepage: http://gondor.apana.org.au/~herbert/dash/
Description-md5: 8d4d9c32c6b2b70328f7f774a0cc1248
Tag: implemented-in::c, interface::shell, role::program, scope::utility
Section: shells
Priority: required
Filename: pool/main/d/dash/dash_0.5.12-12_arm64.deb
Size: 95648
MD5sum: b2c040db45db062d2c48d0eb6e325c3e
SHA256: ce4c8688a1a3ea510186ba95aba74d4c49863094e47e1effc0b602839e840c04

Package: libdb5.3t64
Source: db5.3
Version: 5.3.28+dfsg2-9
Installed-Size: 1775
Maintainer: Debian QA Group <packages@qa.debian.org>
Architecture: arm64
Replaces: libdb5.3
Provides: libdb5.3 (= 5.3.28+dfsg2-9)
Depends: libc6 (>= 2.38)
Breaks: libdb5.3 (<< 5.3.28+dfsg2-9)
Description: Berkeley v5.3 Database Libraries [runtime]
Multi-Arch: same
Homepage: http://www.oracle.com/technetwork/database/database-technologies/berkeleydb/overview/index.html
Description-md5: 6cef0d1fc062f09a5c2c1209dc807be1
Section: libs
Priority: optional
Filename: pool/main/d/db5.3/libdb5.3t64_5.3.28+dfsg2-9_arm64.deb
Size: 629132
MD5sum: 4c79814db6ccb0d94f83060154a085e9
SHA256: bfef56508d69fd3f940668f6abd0a466a362cabf63c4cc1c787cc58d28f6e92f

Package: debconf
Version: 1.5.91
Installed-Size: 497
Maintainer: Debconf Developers <debconf-devel@lists.alioth.debian.org>
Architecture: all
Replaces: debconf-tiny
Provides: debconf-2.0
Recommends: apt (>= 2.9.11~) | apt-utils, debconf-i18n
Suggests: debconf-doc, debconf-kde-helper, debconf-utils, libgtk3-perl, libnet-ldap-perl, libterm-readline-gnu-perl, perl, whiptail | dialog
Conflicts: debconf-tiny, whiptail-utf8 (<= 0.50.17-13)
Description: Debian configuration management system
Multi-Arch: foreign
Description-md5: 85b82bf406dfc9a635114f44ab7fb66d
Tag: admin::configuring, implemented-in::perl, interface::commandline,
 interface::graphical, interface::text-mode, interface::x11,
 role::program, scope::utility, suite::debian, uitoolkit::TODO,
 uitoolkit::gtk, uitoolkit::ncurses, uitoolkit::qt, use::configuring,
 x11::application
Section: admin
Priority: required
Filename: pool/main/d/debconf/debconf_1.5.91_all.deb
Size: 121088
MD5sum: 19f464db8fc065dfbca92198ee28f058
SHA256: 1ddc7e6c5925f748eefb7ca811d98e049f18c158bb21fbc7e122262ce6011e49

Package: debian-archive-keyring
Version: 2025.1
Installed-Size: 302
Maintainer: Debian Release Team <packages@release.debian.org>
Architecture: all
Description: OpenPGP archive certificates of the Debian archive
Multi-Arch: foreign
Description-md5: 9f49cbe8d406bb7cfe6698ebbae08a0a
Tag: role::data, security::authentication, suite::debian
Section: misc
Priority: important
Filename: pool/main/d/debian-archive-keyring/debian-archive-keyring_2025.1_all.deb
Size: 179244
MD5sum: 292a328394db239d2cb6a476a7823e01
SHA256: 9ea7778e443144ca490668737a8ab22dd3e748bb99e805e22ec055abeb3c7fac

Package: debianutils
Version: 5.23.2
Essential: yes
Installed-Size: 369
Maintainer: Ileana Dumitrescu <ileanadumitrescu95@gmail.com>
Architecture: arm64
Pre-Depends: libc6 (>= 2.38)
Breaks: ifupdown (<< 0.8.36+nmu1), printer-driver-pnm2ppa (<< 1.13-12), x11-common (<< 1:7.7+23~)
Description: Miscellaneous utilities specific to Debian
Multi-Arch: foreign
Description-md5: 133cfb7cff1ec5713bc396f059f97013
Tag: admin::configuring, devel::lang:perl, devel::library, implemented-in::c,
 implemented-in::perl, interface::commandline, role::devel-lib,
 role::program, scope::utility, suite::debian
Section: utils
Priority: required
Filename: pool/main/d/debianutils/debianutils_5.23.2_arm64.deb
Size: 92412
MD5sum: b0919e377c0a37de5fcc48aae17e7f5a
SHA256: 662d59d9cd0d46f0250ddcd997353be915fbc6be692d7c676c73dc33c90715e8

Package: diffutils
Version: 1:3.10-4
Essential: yes
Installed-Size: 1876
Maintainer: Santiago Vila <sanvila@debian.org>
Architecture: arm64
Replaces: diff
Pre-Depends: libc6 (>= 2.38)
Suggests: diffutils-doc, wdiff
Description: File comparison utilities
Homepage: https://www.gnu.org/software/diffutils/
Description-md5: 5cf0bc18e36aa2957e62b309d6aa34f9
Tag: devel::rcs, implemented-in::c, interface::commandline, role::program,
 scope::utility, suite::gnu, use::comparing, use::editing,
 use::synchronizing, works-with-format::diff,
 works-with-format::plaintext, works-with::file,
 works-with::software:source, works-with::text
Section: utils
Priority: required
Filename: pool/main/d/diffutils/diffutils_3.10-4_arm64.deb
Size: 377920
MD5sum: dfe9dd35f95d49cc7c9962b5bdf783b5
SHA256: 85cfb5576fa43ba2bd2047c53140fb465b929753048e53eb0fe6482ed343a5b9

Package: dpkg
Version: 1.22.22
Essential: yes
Installed-Size: 6906
Maintainer: Dpkg Developers <debian-dpkg@lists.debian.org>
Architecture: arm64
Depends: tar (>= 1.28-1)
Pre-Depends: libbz2-1.0, libc6 (>= 2.38), liblzma5 (>= 5.4.0), libmd0 (>= 0.0.0), libselinux1 (>= 3.1~), libzstd1 (>= 1.5.5), zlib1g (>= 1:1.1.4)
Suggests: apt, debsig-verify
Breaks: libapt-pkg5.0 (<< 1.7~b), lsb-base (<< 10.2019031300)
Description: Debian package management system
Multi-Arch: foreign
Homepage: https://wiki.debian.org/Teams/Dpkg
Description-md5: 2f156c6a30cc39895ad3487111e8c190
Tag: admin::package-management, devel::lang:perl, devel::library,
 implemented-in::c, implemented-in::perl, interface::commandline,
 role::devel-lib, role::program, scope::utility, suite::debian,
 use::configuring, works-with::software:package
Section: admin
Priority: required
Filename: pool/main/d/dpkg/dpkg_1.22.22_arm64.deb
Size: 1521436
MD5sum: 09b02099c61772a7d65133be22743b38
SHA256: 1142468e57f69e13d174f517dff739508c61ee97d81c182c120cd6b281d8cdfa

Package: findutils
Version: 4.10.0-3
Essential: yes
Installed-Size: 2248
Maintainer: Andreas Metzler <ametzler@debian.org>
Architecture: arm64
Pre-Depends: libc6 (>= 2.38), libselinux1 (>= 3.1~)
Breaks: binstats (<< 1.08-8.1), guilt (<< 0.36-0.2), libpython3.4-minimal (<< 3.4.4-2), libpython3.5-minimal (<< 3.5.1-3), lsat (<< 0.9.7.1-2.1), mc (<< 3:4.8.11-1), switchconf (<< 0.0.9-2.1)
Description: utilities for finding files--find, xargs
Multi-Arch: foreign
Homepage: https://savannah.gnu.org/projects/findutils/
Description-md5: ad1a783819241ffdf3ff5f37a676af59
Tag: implemented-in::c, interface::commandline, role::program,
 scope::utility, suite::gnu, use::searching, works-with::file
Section: utils
Priority: required
Filename: pool/main/f/findutils/findutils_4.10.0-3_arm64.deb
Size: 695700
MD5sum: b91a1c172f5ab09636414ca523627490
SHA256: c8b505ce907c293031095b3a237cb231967963b1d5aa8b1699aff45ccec3158a

Package: gcc-14-base
Source: gcc-14
Version: 14.2.0-19
Installed-Size: 112
Maintainer: Debian GCC Maintainers <debian-gcc@lists.debian.org>
Architecture: arm64
Breaks: gnat (<< 7)
Description: GCC, the GNU Compiler Collection (base package)
Multi-Arch: same
Homepage: http://gcc.gnu.org/
Description-md5: b6e93638a6d08ea7a18929d7cf078e5d
Section: libs
Priority: optional
Filename: pool/main/g/gcc-14/gcc-14-base_14.2.0-19_arm64.deb
Size: 49444
MD5sum: 997abac31f8022d31dcda6d27ab7aa1e
SHA256: 34ee90679b018c0e64234747a4c4c0ae6b7f63541115037465a8627c2dfbc594

Package: libgcc-s1
Source: gcc-14
Version: 14.2.0-19
Installed-Size: 148
Maintainer: Debian GCC Maintainers <debian-gcc@lists.debian.org>
Architecture: arm64
Replaces: libgcc1 (<< 1:10)
Provides: libgcc1 (= 1:14.2.0-19)
Depends: gcc-14-base (= 14.2.0-19), libc6 (>= 2.35)
Breaks: libgcc-7-dev (<< 7.5.0-4), libgcc-8-dev (<< 8.3.0-27), libgcc-9-dev (<< 9.2.1-26)
Description: GCC support library
Multi-Arch: same
Homepage: http://gcc.gnu.org/
Description-md5: bbd60d723e97d8e06c04228ee4c76f10
Important: yes
Protected: yes
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/g/gcc-14/libgcc-s1_14.2.0-19_arm64.deb
Size: 54104
MD5sum: 473debe834d7b77ff74483d6f3fdbd76
SHA256: 1108bc87879833d6d9a145f22a4a15cddb34e065b4b5f4b97bee586adbac2851

Package: libstdc++6
Source: gcc-14
Version: 14.2.0-19
Installed-Size: 3022
Maintainer: Debian GCC Maintainers <debian-gcc@lists.debian.org>
Architecture: arm64
Replaces: libstdc++6-14-dbg (<< 4.9.0-3)
Depends: gcc-14-base (= 14.2.0-19), libc6 (>= 2.38), libgcc-s1 (>= 4.5)
Conflicts: scim (<< 1.4.2-1)
Breaks: gcc-4.3 (<< 4.3.6-1), gcc-4.4 (<< 4.4.6-4), gcc-4.5 (<< 4.5.3-2)
Description: GNU Standard C++ Library v3
Multi-Arch: same
Homepage: http://gcc.gnu.org/
Description-md5: 724ab84919e0e220afb960e36463914d
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/g/gcc-14/libstdc++6_14.2.0-19_arm64.deb
Size: 637536
MD5sum: 55f8a034aaedba9fa762c4890a65de5c
SHA256: 6669b0c52a2e7c6af9adfdabce3ff6e286065cdfbc7b85280862b5f799daebee

Package: libc-bin
Source: glibc
Version: 2.41-12+deb13u3
Essential: yes
Installed-Size: 2249
Maintainer: GNU Libc Maintainers <debian-glibc@lists.debian.org>
Architecture: arm64
Depends: libc6 (>> 2.41), libc6 (<< 2.42)
Recommends: manpages
Breaks: dh-lua (<< 27+nmu1~)
Description: GNU C Library: Binaries
Multi-Arch: foreign
Homepage: https://www.gnu.org/software/libc/libc.html
Description-md5: 23bffd7c48b5ed8394d07f8132ceb64c
Tag: role::shared-lib
Section: libs
Priority: required
Filename: pool/main/g/glibc/libc-bin_2.41-12+deb13u3_arm64.deb
Size: 551364
MD5sum: 5a9d63f88171d9515105d5b09ec24763
SHA256: 02f366115bea79b87fe26c7dec4dc444f9fdcc76440905d88cc26dbef075511b

Package: libc6
Source: glibc
Version: 2.41-12+deb13u3
Installed-Size: 23769
Maintainer: GNU Libc Maintainers <debian-glibc@lists.debian.org>
Architecture: arm64
Depends: libgcc-s1
Recommends: libidn2-0 (>= 2.0.5~)
Suggests: glibc-doc, debconf | debconf-2.0, libc-l10n, locales, libnss-nis, libnss-nisplus
Breaks: base-files (<< 13.3~), chrony (<< 4.2-3~), dhcpcd (<< 1:10.1.0-7~), fakechroot (<< 2.19-3.5), firefox (<< 91~), firefox-esr (<< 91~), gnumach-image-1.8-486 (<< 2:1.8+git20210923~), gnumach-image-1.8-486-dbg (<< 2:1.8+git20210923~), gnumach-image-1.8-xen-486 (<< 2:1.8+git20210923~), gnumach-image-1.8-xen-486-dbg (<< 2:1.8+git20210923~), hurd (<< 1:0.9.git20220301-2), libamdhip64-5 (<< 5.7.1-5+b1), libhiprtc-builtins5 (<< 5.7.1-5+b1), librccl1 (<< 5.4.3-3+b1), libswupdate0.1 (<< 2024.12.1+dfsg-1+b3), locales (<< 2.41), locales-all (<< 2.41), lua-swupdate (<< 2024.12.1+dfsg-1+b3), nscd (<< 2.41), pd-scaf (<< 1:0.14.1+darcs20180201-6+b4), postgresql-15-pllua (<< 1:2.0.12-4), postgresql-17-pllua (<< 1:2.0.12-3+b2), python3-iptables (<< 1.0.0-2), python3-mrgingham (<< 1.25-1), python3-onnxruntime (<< 1.20.1+dfsg-2~), sysvinit (<< 3.09-2~), sysvinit-core (<< 3.09-2~), tinydns (<< 1:1.05-14), uwsgi-plugin-pypy3 (<< 0.0.2+b1), valgrind (<< 1:3.19.0-1~)
Description: GNU C Library: Shared libraries
Multi-Arch: same
Homepage: https://www.gnu.org/software/libc/libc.html
Description-md5: fc3001b0b90a1c8e6690b283a619d57f
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/g/glibc/libc6_2.41-12+deb13u3_arm64.deb
Size: 2489480
MD5sum: 8f52fb06f1a025e23bd532569cd42ae3
SHA256: ff529924782d3286181188fc265a6a92e7fe28975fb3a925dc0e05c0ca66e52f

Package: libgmp10
Source: gmp
Version: 2:6.3.0+dfsg-3
Installed-Size: 846
Maintainer: Debian Science Maintainers <debian-science-maintainers@lists.alioth.debian.org>
Architecture: arm64
Depends: libc6 (>= 2.38)
Breaks: postgresql-pgmp (<< 1.0.3-1)
Description: Multiprecision arithmetic library
Multi-Arch: same
Homepage: https://gmplib.org/
Description-md5: cf6b3a218331a8f680398794acac47a0
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/g/gmp/libgmp10_6.3.0+dfsg-3_arm64.deb
Size: 535088
MD5sum: fba7666457b0d37eb83eb693fdcf8562
SHA256: a27bbc27f119161ea9702c8dd66f54131cdf0d2ca73000f50ea91ef2fdfef0fb

Package: grep
Source: grep (3.11-4)
Version: 3.11-4+b1
Essential: yes
Installed-Size: 1263
Maintainer: Anibal Monsalve Salazar <anibal@debian.org>
Architecture: arm64
Provides: rgrep
Pre-Depends: libc6 (>= 2.38), libpcre2-8-0 (>= 10.32)
Conflicts: rgrep
Description: GNU grep, egrep and fgrep
Multi-Arch: foreign
Homepage: https://www.gnu.org/software/grep/
Description-md5: f9188c5583d41955f3b3fe60b9d445f1
Tag: implemented-in::c, interface::commandline, role::program,
 scope::utility, suite::gnu, use::filtering, use::searching,
 works-with::file, works-with::text
Section: utils
Priority: required
Filename: pool/main/g/grep/grep_3.11-4+b1_arm64.deb
Size: 426128
MD5sum: d4ddfd57fcaabe16ef8443305ef15adc
SHA256: 6f7257fbec0197bd54b2347861ff01e2c4e8411790b0df20242483615324fe66

Package: gzip
Version: 1.13-1
Essential: yes
Installed-Size: 288
Maintainer: Milan Kupcevic <milan@debian.org>
Architecture: arm64
Pre-Depends: libc6 (>= 2.38)
Suggests: less
Conflicts: zutils (<< 1.13-2~)
Description: GNU compression utilities
Homepage: https://www.gnu.org/software/gzip/
Description-md5: 100720c9e2c6508f1a1f3731537b38e5
Tag: implemented-in::c, interface::commandline, role::program,
 scope::utility, suite::gnu, use::compressing, works-with::archive,
 works-with::file
Section: utils
Priority: required
Filename: pool/main/g/gzip/gzip_1.13-1_arm64.deb
Size: 135008
MD5sum: 234d86bb175c262044cc6d9c23952459
SHA256: cd46c9482fd3b96d4ecf282b01185ef9341b0ffcd40816372141f73037ce2c36

Package: hostname
Version: 3.25
Essential: yes
Installed-Size: 90
Maintainer: Michael Meskes <meskes@debian.org>
Architecture: arm64
Replaces: nis (<< 3.17-30)
Pre-Depends: libc6 (>= 2.34)
Breaks: nis (<< 3.17-30)
Description: utility to set/show the host name or domain name
Description-md5: a5a22acc3c69a7f40f07f1a8dfc93af1
Tag: implemented-in::c, interface::commandline, role::program,
 scope::utility, use::configuring
Section: admin
Priority: required
Filename: pool/main/h/hostname/hostname_3.25_arm64.deb
Size: 10808
MD5sum: 9db3ae22f641523145dfeee081f4698b
SHA256: 04d1ff24cb605788b59ed30d0052c1039c8bce2fb44c01c3c4b0c6edd4ea7ccc

Package: init-system-helpers
Version: 1.69~deb13u1
Essential: yes
Installed-Size: 133
Maintainer: Debian systemd Maintainers <pkg-systemd-maintainers@lists.alioth.debian.org>
Architecture: all
Description: helper tools for all init systems
Multi-Arch: foreign
Description-md5: facafbf6c4b9fd95c34e95938629ecef
Tag: admin::boot, implemented-in::perl, role::program
Section: admin
Priority: required
Filename: pool/main/i/init-system-helpers/init-system-helpers_1.69~deb13u1_all.deb
Size: 39360
MD5sum: 48f451739effa45774b7b78d51671fdb
SHA256: addf78073e1cbc5a5508937e11d2571055e29835e00ee8fab52a66775649378f

Package: libbsd0
Source: libbsd
Version: 0.12.2-2
Installed-Size: 262
Maintainer: Guillem Jover <guillem@debian.org>
Architecture: arm64
Depends: libc6 (>= 2.38), libmd0 (>= 1.0.3-2)
Description: utility functions from BSD systems - shared library
Multi-Arch: same
Homepage: https://libbsd.freedesktop.org/
Description-md5: 85853962ecf54d2af43ee57974a0c0ed
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/libb/libbsd/libbsd0_0.12.2-2_arm64.deb
Size: 128944
MD5sum: a12346422b7e5f978fd40af79e8e80ca
SHA256: 6827c1b3ad8dcbf3478803f9150359433e3376a3bde01c4503888a22eb3924e3

Package: libcap-ng0
Source: libcap-ng (0.8.5-4)
Version: 0.8.5-4+b1
Installed-Size: 154
Maintainer: Håvard F. Aasen <havard.f.aasen@pfft.no>
Architecture: arm64
Depends: libc6 (>= 2.38)
Description: alternate POSIX capabilities library
Multi-Arch: same
Homepage: https://people.redhat.com/sgrubb/libcap-ng
Description-md5: 43e332337e845c9ae5422001e67a1209
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/libc/libcap-ng/libcap-ng0_0.8.5-4+b1_arm64.deb
Size: 17032
MD5sum: b0e3467cb8ed52dff5aec010e641b5ce
SHA256: eb6da83296e4710a8600c1426692ed9b9eadcab7ebd36dbabc2a562b5c1ab691

Package: libcap2
Source: libcap2 (1:2.75-10+deb13u1)
Version: 1:2.75-10+deb13u1+b1
Installed-Size: 160
Maintainer: Christian Kastner <ckk@debian.org>
Architecture: arm64
Depends: libc6 (>= 2.38)
Description: POSIX 1003.1e capabilities (library)
Multi-Arch: same
Homepage: https://sites.google.com/site/fullycapable/
Description-md5: cd474c07928607f123b20712016d716e
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/libc/libcap2/libcap2_2.75-10+deb13u1+b1_arm64.deb
Size: 28424
MD5sum: 50309905f8b68c2a78bb54640b600313
SHA256: c9a77aa670c9ca163c624a00e55c4419df40b770fe516010dc495c04a16c80b0

Package: libmd0
Source: libmd (1.1.0-2)
Version: 1.1.0-2+b1
Installed-Size: 102
Maintainer: Guillem Jover <guillem@debian.org>
Architecture: arm64
Depends: libc6 (>= 2.33)
Description: message digest functions from BSD systems - shared library
Multi-Arch: same
Homepage: https://www.hadrons.org/software/libmd/
Description-md5: 9f178d99a8400fec0a8ae1aa28518f9b
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/libm/libmd/libmd0_1.1.0-2+b1_arm64.deb
Size: 33664
MD5sum: dff2532846e38b6debcf9b0d6927c826
SHA256: 772e86842fd3fa1816a0cfee4833a73996aaa550461491543b886b9c8f999052

Package: libseccomp2
Source: libseccomp
Version: 2.6.0-2
Installed-Size: 217
Maintainer: Kees Cook <kees@debian.org>
Architecture: arm64
Depends: libc6 (>= 2.17)
Description: high level interface to Linux seccomp filter
Multi-Arch: same
Homepage: https://github.com/seccomp/libseccomp
Description-md5: 7ee97a8161e83bfebc75870eb92bde51
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/libs/libseccomp/libseccomp2_2.6.0-2_arm64.deb
Size: 51012
MD5sum: 9a6ec6b41e1d9ff664b1f6800c09e4d1
SHA256: 013d0af0444177960d5899f4673e35d492f31edfc40cb8d5a82bb56147ab2e35

Package: libselinux1
Source: libselinux
Version: 3.8.1-1
Installed-Size: 289
Maintainer: Debian SELinux maintainers <selinux-devel@lists.alioth.debian.org>
Architecture: arm64
Depends: libc6 (>= 2.38), libpcre2-8-0 (>= 10.22)
Description: SELinux runtime shared libraries
Multi-Arch: same
Homepage: https://github.com/SELinuxProject
Description-md5: 90f6e1cb06c527bc3fc11ec6f969c59c
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/libs/libselinux/libselinux1_3.8.1-1_arm64.deb
Size: 79352
MD5sum: 1d49e99295c51fafc01829ddb97b433b
SHA256: fc13da4c7783b8f4361d92ef1045931f298d1b0ac78ed1a85c2232615f49fc6f

Package: libsemanage-common
Source: libsemanage
Version: 3.8.1-1
Installed-Size: 21
Maintainer: Debian SELinux maintainers <selinux-devel@lists.alioth.debian.org>
Architecture: all
Description: Common files for SELinux policy management libraries
Multi-Arch: foreign
Homepage: https://selinuxproject.org
Description-md5: c7a0a093650c85e838f42492add9b46b
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/libs/libsemanage/libsemanage-common_3.8.1-1_all.deb
Size: 7812
MD5sum: e27a7f67a7f2d8a78ccc5d7ff402a103
SHA256: 65375d886749c236bd1da4d9990b7c2469d2fe66019a28e5614f6814376d3dcb

Package: libsemanage2
Source: libsemanage
Version: 3.8.1-1
Installed-Size: 354
Maintainer: Debian SELinux maintainers <selinux-devel@lists.alioth.debian.org>
Architecture: arm64
Depends: libsemanage-common (>= 3.8.1-1), libaudit1 (>= 1:2.2.1), libbz2-1.0, libc6 (>= 2.38), libselinux1 (>= 3.8.1), libsepol2 (>= 3.8.1)
Description: SELinux policy management library
Multi-Arch: same
Homepage: https://selinuxproject.org
Description-md5: 8998b538051f37f69c5f1bf9a005fa56
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/libs/libsemanage/libsemanage2_3.8.1-1_arm64.deb
Size: 87840
MD5sum: f90a46e62f80ae20d81b9af869bdae75
SHA256: ed73a76d3c39ac5cbd0a447527bba3dc33d3ec3785af7583b70ab41f85fc0243

Package: libsepol2
Source: libsepol
Version: 3.8.1-1
Installed-Size: 863
Maintainer: Debian SELinux maintainers <selinux-devel@lists.alioth.debian.org>
Architecture: arm64
Depends: libc6 (>= 2.38)
Description: SELinux library for manipulating binary security policies
Multi-Arch: same
Homepage: https://selinuxproject.org
Description-md5: d569fae7efa3328b3d40879b104d9a63
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/libs/libsepol/libsepol2_3.8.1-1_arm64.deb
Size: 266256
MD5sum: 18c9d3b0c5cb95b53d4e7b3a31a2f32b
SHA256: 0119cbb4d12a6b5093430a00714019bac87a446f34f21d834453742f1af35b8b

Package: libcrypt1
Source: libxcrypt
Version: 1:4.4.38-1
Installed-Size: 227
Maintainer: Marco d'Itri <md@linux.it>
Architecture: arm64
Replaces: libc6 (<< 2.29-4)
Depends: libc6 (>= 2.38)
Conflicts: libpam0g (<< 1.4.0-10)
Description: libcrypt shared library
Multi-Arch: same
Description-md5: deac8c9adc422e925bdaf6bffdd3e875
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/libx/libxcrypt/libcrypt1_4.4.38-1_arm64.deb
Size: 91760
MD5sum: 9c8657cea389511f36165ba800722eeb
SHA256: b9aaa808aa3812b3de3e54eb5dfb16762c1779db032604abef2354c02fdc29b8

Package: libzstd1
Source: libzstd
Version: 1.5.7+dfsg-1
Installed-Size: 748
Maintainer: RPM packaging team <team+pkg-rpm@tracker.debian.org>
Architecture: arm64
Depends: libc6 (>= 2.34)
Description: fast lossless compression algorithm
Multi-Arch: same
Homepage: https://github.com/facebook/zstd
Description-md5: 5529781042229a3d4fc1e69daa94d736
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/libz/libzstd/libzstd1_1.5.7+dfsg-1_arm64.deb
Size: 266052
MD5sum: 439dda611ba0b0d0465d5f2a373c43fe
SHA256: 924540bd59fdbfa77a0604360efdaca54411a43daf11c7e002a3c64791b67448

Package: liblz4-1
Source: lz4
Version: 1.10.0-4
Installed-Size: 217
Maintainer: Nobuhiro Iwamatsu <iwamatsu@debian.org>
Architecture: arm64
Replaces: liblz4-1a
Depends: libc6 (>= 2.17), libxxhash0 (>= 0.6.5)
Breaks: liblz4-1a
Description: Fast LZ compression algorithm library - runtime
Multi-Arch: same
Homepage: https://github.com/lz4/lz4
Description-md5: c21cacb9494ced3b87993140a583422c
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/l/lz4/liblz4-1_1.10.0-4_arm64.deb
Size: 59988
MD5sum: 1dfdb4f42dba7cf8cbac006f382e51bb
SHA256: f86fa7f8112d56b74f99c4a8baade57a9962d187d7c8e319428e171b2a34d223

Package: mawk
Version: 1.3.4.20250131-1
Installed-Size: 320
Maintainer: Boyuan Yang <byang@debian.org>
Architecture: arm64
Provides: awk
Depends: libc6 (>= 2.38)
Description: Pattern scanning and text processing language
Multi-Arch: foreign
Homepage: https://invisible-island.net/mawk/
Description-md5: 05965bfcd1a2333e963d9f1945d161fc
Tag: devel::interpreter, implemented-in::c, interface::commandline,
 role::program, scope::utility, use::filtering, use::scanning,
 works-with::text
Section: interpreters
Priority: required
Filename: pool/main/m/mawk/mawk_1.3.4.20250131-1_arm64.deb
Size: 133820
MD5sum: 47cd68bdaf2ade91f27a2fc1ffd9c313
SHA256: 0ab946ff4b8a34d93f81727957612f290c86cd0db39e849dc0ae5e3f3a9563d6

Package: libtinfo6
Source: ncurses
Version: 6.5+20250216-2
Installed-Size: 673
Maintainer: Ncurses Maintainers <ncurses@packages.debian.org>
Architecture: arm64
Depends: libc6 (>= 2.34)
Breaks: tmux (<< 3.3a-4)
Description: shared low-level terminfo library for terminal handling
Multi-Arch: same
Homepage: https://invisible-island.net/ncurses/
Description-md5: f681846d99e5156a0882bb53c35d3244
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/n/ncurses/libtinfo6_6.5+20250216-2_arm64.deb
Size: 341360
MD5sum: 8e8ca4096d7cef688cde8918acef9e2f
SHA256: 2a747f7fff3c2347c357387a7133df997410f07ba1b1df1a1461a97bd7fbc2fc

Package: ncurses-base
Source: ncurses
Version: 6.5+20250216-2
Essential: yes
Installed-Size: 389
Maintainer: Ncurses Maintainers <ncurses@packages.debian.org>
Architecture: all
Replaces: ncurses-term (<< 6.4+20230603)
Provides: ncurses-runtime
Breaks: cryptsetup-initramfs (<< 2:2.6.1), ncurses-term (<< 6.4+20230625), neovim (<< 0.6.0), vim-common (<< 2:9.0.1000-2)
Description: basic terminal type definitions
Multi-Arch: foreign
Homepage: https://invisible-island.net/ncurses/
Description-md5: 2cbef17322c0de7f007682de54ca0d4a
Tag: implemented-in::c, role::app-data, uitoolkit::ncurses
Section: misc
Priority: required
Filename: pool/main/n/ncurses/ncurses-base_6.5+20250216-2_all.deb
Size: 273216
MD5sum: ee4d5cfcc6a5958ba8f87e907fcffa7e
SHA256: 190e775260a38973c415a36ae99f33ad1b37ea6ca99929d7c5d53843fc23e914

Package: ncurses-bin
Source: ncurses
Version: 6.5+20250216-2
Essential: yes
Installed-Size: 917
Maintainer: Ncurses Maintainers <ncurses@packages.debian.org>
Architecture: arm64
Pre-Depends: libc6 (>= 2.34), libtinfo6 (>= 6.4+20230603)
Description: terminal-related programs and man pages
Multi-Arch: foreign
Homepage: https://invisible-island.net/ncurses/
Description-md5: 682ee2624c08c54a53ecaefd778a4d86
Tag: admin::configuring, implemented-in::c, interface::commandline,
 interface::text-mode, role::program, scope::utility, uitoolkit::ncurses
Section: utils
Priority: required
Filename: pool/main/n/ncurses/ncurses-bin_6.5+20250216-2_arm64.deb
Size: 432224
MD5sum: 32a9f31177ee494de787f37e365056bc
SHA256: 7ac6919efb0effc9004caae758811bfe85bb486a057262822468e90d73d38458

Package: libhogweed6t64
Source: nettle
Version: 3.10.1-1
Installed-Size: 507
Maintainer: Magnus Holmgren <holmgren@debian.org>
Architecture: arm64
Replaces: libhogweed6
Provides: libhogweed6 (= 3.10.1-1)
Depends: libc6 (>= 2.17), libgmp10 (>= 2:6.3.0+dfsg), libnettle8t64
Breaks: libhogweed6 (<< 3.10.1-1)
Description: low level cryptographic library (public-key cryptos)
Multi-Arch: same
Homepage: https://www.lysator.liu.se/~nisse/nettle/
Description-md5: 2a20554eaadbd355079396956d5d3edb
Section: libs
Priority: optional
Filename: pool/main/n/nettle/libhogweed6t64_3.10.1-1_arm64.deb
Size: 331628
MD5sum: bf229cbc4829f321e0ac56928b21375c
SHA256: cb92d5a51c4fd6c7b7cbb62aaa60c7af830d0bea5b410fb1f47ee685e26944d1

Package: libnettle8t64
Source: nettle
Version: 3.10.1-1
Installed-Size: 543
Maintainer: Magnus Holmgren <holmgren@debian.org>
Architecture: arm64
Replaces: libnettle8
Provides: libnettle8 (= 3.10.1-1)
Depends: libc6 (>= 2.17)
Breaks: libnettle8 (<< 3.10.1-1)
Description: low level cryptographic library (symmetric and one-way cryptos)
Multi-Arch: same
Homepage: https://www.lysator.liu.se/~nisse/nettle/
Description-md5: 9dcb77a61a93b06eb5b005f9c8c509a4
Section: libs
Priority: optional
Filename: pool/main/n/nettle/libnettle8t64_3.10.1-1_arm64.deb
Size: 310548
MD5sum: d1ebde38ee25612bc7532380019fe7d1
SHA256: 16107ce5a7b522da021fa8aba42d42af9c5e90ddabfcda3710aff6ea95808ee0

Package: libssl3t64
Source: openssl
Version: 3.5.6-1~deb13u2
Installed-Size: 8015
Maintainer: Debian OpenSSL Team <pkg-openssl-devel@alioth-lists.debian.net>
Architecture: arm64
Replaces: libssl3
Provides: libssl3 (= 3.5.6-1~deb13u2)
Depends: libc6 (>= 2.38), libzstd1 (>= 1.5.5), zlib1g (>= 1:1.1.4), openssl-provider-legacy
Breaks: freeradius (<< 3.2.7+dfsg-1+deb13u1), libssl3 (<< 3.5.6-1~deb13u2), openssh-client (<< 1:9.4p1), openssh-server (<< 1:9.4p1), python3-m2crypto (<< 0.38.0-4)
Description: Secure Sockets Layer toolkit - shared libraries
Multi-Arch: same
Homepage: https://openssl-library.org
Description-md5: 88547c6206c7fbc4fcc7d09ce100d210
Section: libs
Priority: optional
Filename: pool/main/o/openssl/libssl3t64_3.5.6-1~deb13u2_arm64.deb
Size: 2760128
MD5sum: 0f47eb2bb5c575033bd86d153b6e91c6
SHA256: f7e09a12ccb2d6bd28bcc87a6e2462a0558dcb3cc34d56f90ee86fbd3402d8ba

Package: openssl
Version: 3.5.6-1~deb13u2
Installed-Size: 2550
Maintainer: Debian OpenSSL Team <pkg-openssl-devel@alioth-lists.debian.net>
Architecture: arm64
Depends: libc6 (>= 2.34), libssl3t64 (>= 3.5.0)
Suggests: ca-certificates
Description: Secure Sockets Layer toolkit - cryptographic utility
Multi-Arch: foreign
Homepage: https://openssl-library.org
Description-md5: 9b6de2bb6e1d9016aeb0f00bcf6617bd
Tag: implemented-in::c, interface::commandline, protocol::ssl, role::program,
 scope::utility, security::cryptography, security::integrity,
 use::checking
Section: utils
Priority: optional
Filename: pool/main/o/openssl/openssl_3.5.6-1~deb13u2_arm64.deb
Size: 1466984
MD5sum: 8e4a5e4460f82f4a311c049e07737108
SHA256: 1ee735e2480519bd1fd5f6576a879243bc81b9773059e1fa1ec8b8943c84e77c

Package: openssl-provider-legacy
Source: openssl
Version: 3.5.6-1~deb13u2
Installed-Size: 412
Maintainer: Debian OpenSSL Team <pkg-openssl-devel@alioth-lists.debian.net>
Architecture: arm64
Replaces: libssl3 (<< 3.3.1-5), libssl3t64 (<< 3.3.1-5)
Depends: libc6 (>= 2.17), libssl3t64 (>= 3.0.3)
Breaks: libssl3 (<< 3.3.1-5), libssl3t64 (<< 3.3.1-5)
Description: Secure Sockets Layer toolkit - cryptographic utility
Multi-Arch: foreign
Homepage: https://openssl-library.org
Description-md5: 6ae6f8ddd6448e07b5d86dc9b813dddd
Section: utils
Priority: optional
Filename: pool/main/o/openssl/openssl-provider-legacy_3.5.6-1~deb13u2_arm64.deb
Size: 310184
MD5sum: 21c0d10775611ac1a50b79ddcb215e64
SHA256: b099729f6a38d7d57f7f442f0f836c862673b9bb0ee65d757da6e3cdebbfd2a4

Package: libpam-modules
Source: pam
Version: 1.7.0-5
Installed-Size: 3055
Maintainer: Sam Hartman <hartmans@debian.org>
Architecture: arm64
Replaces: libpam-umask, libpam0g-util
Provides: libpam-mkhomedir, libpam-motd, libpam-umask
Pre-Depends: libaudit1 (>= 1:2.2.1), libc6 (>= 2.38), libcrypt1 (>= 1:4.3.0), libdb5.3t64, libpam0g (>= 1.4.1), libselinux1 (>= 3.1~), libsystemd0 (>= 254), debconf (>= 0.5) | debconf-2.0, libpam-modules-bin (= 1.7.0-5)
Conflicts: libpam-mkhomedir, libpam-motd, libpam-umask
Description: Pluggable Authentication Modules for PAM
Multi-Arch: same
Homepage: http://www.linux-pam.org/
Description-md5: 234b9429528430ead853cc8bbe97ffb4
Tag: implemented-in::c, role::plugin, security::authentication
Section: admin
Priority: required
Filename: pool/main/p/pam/libpam-modules_1.7.0-5_arm64.deb
Size: 172452
MD5sum: cd3be4eb5319bbdb9c7096e647371887
SHA256: 3c6c4b0be396f2a90d150effd480e06674d7d22739fe4c54ffbf95587fe2bcf4

Package: libpam-modules-bin
Source: pam
Version: 1.7.0-5
Installed-Size: 446
Maintainer: Sam Hartman <hartmans@debian.org>
Architecture: arm64
Replaces: libpam-modules (<< 1.5.2-5~)
Depends: libaudit1 (>= 1:2.2.1), libc6 (>= 2.38), libcrypt1 (>= 1:4.3.0), libpam0g (>= 0.99.7.1), libselinux1 (>= 3.1~), libsystemd0 (>= 254)
Description: Pluggable Authentication Modules for PAM - helper binaries
Multi-Arch: foreign
Homepage: http://www.linux-pam.org/
Description-md5: 25d278fc7450d5202a9a137f71302e58
Tag: admin::login, admin::user-management, implemented-in::c, protocol::ldap,
 role::plugin, role::program, role::shared-lib,
 security::authentication, use::login
Section: admin
Priority: required
Filename: pool/main/p/pam/libpam-modules-bin_1.7.0-5_arm64.deb
Size: 48272
MD5sum: 8048f3eebfb6abff174959ab5ca5d668
SHA256: 8c50ad191934474b5177cba48aed2c4023ec8392ef7096808311315c50b3bfec

Package: libpam-runtime
Source: pam
Version: 1.7.0-5
Installed-Size: 1028
Maintainer: Sam Hartman <hartmans@debian.org>
Architecture: all
Replaces: libpam-modules (<< 1.7.0), libpam-modules-bin (<< 1.7.0)
Depends: debconf (>= 0.5) | debconf-2.0, debconf (>= 1.5.19) | cdebconf, libpam-modules (>= 1.0.1-6)
Breaks: libpam-modules (<< 1.7.0), libpam-modules-bin (<< 1.7.0)
Description: Runtime support for the PAM library
Multi-Arch: foreign
Homepage: http://www.linux-pam.org/
Description-md5: bc15ddbf92ee7965a8588141c54bb5a1
Tag: implemented-in::c, role::app-data, role::program,
 security::authentication, use::configuring
Section: admin
Priority: required
Filename: pool/main/p/pam/libpam-runtime_1.7.0-5_all.deb
Size: 248720
MD5sum: 33352d4b91bffa6bf074b837862f8029
SHA256: b02792c9ed6174db695a653b95ef3629fdac3a73ba1eff1754ab161b5711bfbf

Package: libpam0g
Source: pam
Version: 1.7.0-5
Installed-Size: 295
Maintainer: Sam Hartman <hartmans@debian.org>
Architecture: arm64
Replaces: libpam0g-util, libpam0t64 (<< 1.5.3-5)
Depends: libaudit1 (>= 1:2.2.1), libc6 (>= 2.34), debconf (>= 0.5) | debconf-2.0
Suggests: libpam-doc
Breaks: libpam0t64 (<< 1.5.3-5)
Description: Pluggable Authentication Modules library
Multi-Arch: same
Homepage: http://www.linux-pam.org/
Description-md5: af00a40029e1e1d2ad04c042c3b18095
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/p/pam/libpam0g_1.7.0-5_arm64.deb
Size: 68776
MD5sum: 34f1d982ce27320ba9e5478766f77bd6
SHA256: 2dd0fdc9af8a8a48f0a54581f74005d4d8623baa8aea9ac3d201624c3217b6d3

Package: libpcre2-8-0
Source: pcre2
Version: 10.46-1~deb13u1
Installed-Size: 722
Maintainer: Matthew Vernon <matthew@debian.org>
Architecture: arm64
Depends: libc6 (>= 2.34)
Description: New Perl Compatible Regular Expression Library- 8 bit runtime files
Multi-Arch: same
Homepage: https://github.com/PCRE2Project/pcre2
Description-md5: c7a3d45ed09bfb032aada6998b70c4e1
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/p/pcre2/libpcre2-8-0_10.46-1~deb13u1_arm64.deb
Size: 262004
MD5sum: a8f98115813c7f27048a6b90b4bfb6e0
SHA256: f74d3e1082c2d6d2f7b4d47e41070de4a7e2f7f9d0e6e0c901ebfe0e82cdd3ee

Package: perl-base
Source: perl
Version: 5.40.1-6
Essential: yes
Installed-Size: 8006
Maintainer: Niko Tyni <ntyni@debian.org>
Architecture: arm64
Replaces: libfile-path-perl (<< 2.18), libfile-temp-perl (<< 0.2311), libio-socket-ip-perl (<< 0.42), libscalar-list-utils-perl (<< 1:1.63), libsocket-perl (<< 2.038), libxsloader-perl (<< 0.32), perl (<< 5.10.1-12), perl-modules (<< 5.20.1-3)
Provides: libfile-path-perl (= 2.18), libfile-temp-perl (= 0.2311), libio-socket-ip-perl (= 0.42), libscalar-list-utils-perl (= 1:1.63), libsocket-perl (= 2.038), libxsloader-perl (= 0.32), perlapi-5.40.0, perlapi-5.40.1
Pre-Depends: libc6 (>= 2.38), libcrypt1 (>= 1:4.1.0)
Suggests: perl, sensible-utils
Conflicts: defoma (<< 0.11.12), doc-base (<< 0.10.3), mono-gac (<< 2.10.8.1-3), safe-rm (<< 0.8), update-inetd (<< 4.41)
Breaks: amanda-common (<< 1:3.3.9-2), backuppc (<< 4.4.0-7~), bucardo (<< 5.5.0-1.1), debconf (<< 1.5.61), dh-haskell (<< 0.3), duck (<< 0.14.1), intltool (<< 0.51.0-4), kio-perldoc (<< 20.04.1-1), latexml (<< 0.8.4-2), libdevel-mat-dumper-perl (<< 0.42-3), libencode-arabic-perl (<< 14.2-2), libexception-class-perl (<< 1.42), libfile-path-perl (<< 2.18), libfile-spec-perl (<< 3.9100), libfile-temp-perl (<< 0.2311), libgnupg-interface-perl (<< 1.02-4), libio-socket-ip-perl (<< 0.42), liblexical-failure-perl (<< 0.001000), libmp3-tag-perl (<< 1.13-1.2), libnumber-format-perl (<< 1.76), libsbuild-perl (<< 0.67.0-1), libscalar-list-utils-perl (<< 1:1.63), libsocket-perl (<< 2.038), libur-perl (<< 0.470+ds-3), libxsloader-perl (<< 0.32), mailagent (<< 1:3.1-81-2), perl (<< 5.40.1~), perl-modules (<< 5.40.1~), pod2pdf (<< 0.42-5.1), slic3r (<< 1.2.9+dfsg-6.1), slic3r-prusa (<< 1.37.0+dfsg-1.1), texinfo (<< 6.1.0.dfsg.1-8)
Description: minimal Perl system
Homepage: https://dev.perl.org/perl5/
Description-md5: 71551f199284cd26b0352827389f8b4b
Tag: devel::interpreter, devel::lang:perl, devel::library, implemented-in::c,
 implemented-in::perl, interface::commandline, role::devel-lib,
 role::program, role::shared-lib
Section: perl
Priority: required
Filename: pool/main/p/perl/perl-base_5.40.1-6_arm64.deb
Size: 1526636
MD5sum: d7724d307a4a6a612059767fc3f4b3ef
SHA256: 65eef1a545207309b9ce87139be81be9dd12f24b0bd12be935cb36e51bdb6f2d

Package: sqv
Source: rust-sequoia-sqv (1.3.0-3)
Version: 1.3.0-3+b2
Installed-Size: 1631
Maintainer: Debian Rust Maintainers <pkg-rust-maintainers@alioth-lists.debian.net>
Architecture: arm64
Depends: libc6 (>= 2.34), libgcc-s1 (>= 4.2), libgmp10 (>= 2:6.3.0+dfsg), libhogweed6t64, libnettle8t64 (>= 3.9~)
Description: OpenPGP signature verification program from Sequoia
Multi-Arch: foreign
Homepage: https://sequoia-pgp.org/
Built-Using: rust-buffered-reader (= 1.3.1-2), rust-nettle (= 7.3.0-1), rust-nettle-sys (= 2.3.1-1), rust-sequoia-openpgp (= 2.0.0-2+deb13u1), rust-sequoia-policy-config (= 0.8.0-1), rustc (= 1.85.0+dfsg3-1)
Description-md5: 5beed3801dc38c218323d79a5050eb24
Static-Built-Using: rust-aho-corasick (= 1.1.3-1), rust-anstream (= 0.6.15-1), rust-anstyle (= 1.0.8-1), rust-anstyle-parse (= 0.2.1-1), rust-anstyle-query (= 1.0.0-1), rust-anyhow (= 1.0.95-1), rust-argon2 (= 0.5.3-1), rust-base64 (= 0.22.1-1), rust-base64ct (= 1.6.0-1), rust-bitflags (= 2.8.0-1), rust-blake2 (= 0.10.6-3), rust-block-buffer (= 0.10.4-1), rust-buffered-reader (= 1.3.1-2), rust-cfg-if (= 1.0.0-1), rust-chrono (= 0.4.39-2), rust-clap (= 4.5.23-1), rust-clap-builder (= 4.5.23-1), rust-clap-lex (= 0.7.4-1), rust-colorchoice (= 1.0.0-1), rust-crypto-common (= 0.1.6-1), rust-digest (= 0.10.7-2), rust-dyn-clone (= 1.0.16-1), rust-generic-array (= 0.14.7-1), rust-getrandom (= 0.2.15-1), rust-iana-time-zone (= 0.1.63-1), rust-idna (= 1.0.3-2), rust-idna-adapter (= 1.1.0-1), rust-idna-mapping (= 1.0.0-1), rust-lalrpop-util (= 0.20.0-1), rust-lazy-static (= 1.5.0-1), rust-libc (= 0.2.169-1), rust-linux-raw-sys (= 0.4.14-1), rust-memchr (= 2.7.4-1), rust-memsec (= 0.7.0-1), rust-nettle (= 7.3.0-1), rust-nettle-sys (= 2.3.1-1), rust-num-traits (= 0.2.19-2), rust-password-hash (= 0.5.0-1), rust-rand-core (= 0.6.4-2), rust-regex (= 1.11.1-2), rust-regex-automata (= 0.4.9-2), rust-regex-syntax (= 0.8.5-1), rust-rustix (= 0.38.37-4), rust-sequoia-openpgp (= 2.0.0-2+deb13u1), rust-sequoia-policy-config (= 0.8.0-1), rust-serde (= 1.0.217-1), rust-sha1collisiondetection (= 0.3.2-1), rust-smallvec (= 1.13.2-1), rust-strsim-0.10 (= 0.10.0-4), rust-subtle (= 2.6.1-1), rust-terminal-size (= 0.4.1-2), rust-thiserror (= 2.0.11-1), rust-thiserror-1 (= 1.0.69-1), rust-tinyvec (= 1.6.0-2), rust-tinyvec-macros (= 0.1.0-1), rust-toml-0.5 (= 0.5.11-4), rust-typenum (= 1.17.0-3), rust-unicode-bidi (= 0.3.17-1), rust-unicode-joining-type (= 1.0.0-1), rust-unicode-normalization (= 0.1.24-1), rust-utf8-iter (= 1.0.4-1), rust-utf8parse (= 0.2.1-1), rust-xxhash-rust (= 0.8.15-1), rustc (= 1.85.0+dfsg3-1)
Section: utils
Priority: optional
Filename: pool/main/r/rust-sequoia-sqv/sqv_1.3.0-3+b2_arm64.deb
Size: 611508
MD5sum: 69851acef157c0797af3f82f68b0a639
SHA256: feb611506f420594bf767b1a8748429802ff4f324536ab1154bcc61478b9a8b6

Package: sed
Version: 4.9-2+deb13u1
Essential: yes
Installed-Size: 995
Maintainer: Clint Adams <clint@debian.org>
Architecture: arm64
Pre-Depends: libacl1 (>= 2.2.23), libc6 (>= 2.38), libselinux1 (>= 3.1~)
Description: GNU stream editor for filtering/transforming text
Multi-Arch: foreign
Homepage: https://www.gnu.org/software/sed/
Description-md5: 2ed71305ee7a49ce4438c58140980d2f
Tag: implemented-in::c, interface::commandline, role::program,
 scope::utility, suite::gnu, use::editing, works-with::file,
 works-with::text
Section: utils
Priority: required
Filename: pool/main/s/sed/sed_4.9-2+deb13u1_arm64.deb
Size: 325712
MD5sum: 1e8240467fa1d1cf8095244eae665dbb
SHA256: c654fa045de8cc722855b8db59990b703cb7299c897c6e187bf949d42df82b62

Package: login.defs
Source: shadow
Version: 1:4.17.4-2
Installed-Size: 214
Maintainer: Shadow package maintainers <pkg-shadow-devel@lists.alioth.debian.org>
Architecture: all
Replaces: login (<< 1:4.16.0-2~)
Description: system user management configuration
Multi-Arch: foreign
Homepage: https://github.com/shadow-maint/shadow
Description-md5: 4085dc032cb3ecfa269c4c2c2b85249c
Section: admin
Priority: required
Filename: pool/main/s/shadow/login.defs_4.17.4-2_all.deb
Size: 186124
MD5sum: b6eb2480a7da34d774e68f6223f275ac
SHA256: b4bbb8242cbf5588de7aafeb9d578a3dee1db2891f444ab454fd30bd80ccf2b4

Package: passwd
Source: shadow
Version: 1:4.17.4-2
Installed-Size: 5317
Maintainer: Shadow package maintainers <pkg-shadow-devel@lists.alioth.debian.org>
Architecture: arm64
Replaces: login (<< 1:4.16.0-2~)
Depends: base-passwd (>= 3.6.4), libpam-modules, login.defs, libacl1 (>= 2.2.23), libattr1 (>= 1:2.4.44), libaudit1 (>= 1:2.2.1), libbsd0 (>= 0.11.0), libc6 (>= 2.38), libcrypt1 (>= 1:4.1.0), libpam0g (>= 0.99.7.1), libselinux1 (>= 3.1~), libsemanage2 (>= 2.0.32)
Recommends: sensible-utils
Description: change and administer password and group data
Multi-Arch: foreign
Homepage: https://github.com/shadow-maint/shadow
Description-md5: 5bbd70e421ed3367a8299e53bd7afed4
Tag: admin::user-management, implemented-in::c, interface::commandline,
 role::program, security::authentication
Section: admin
Priority: required
Filename: pool/main/s/shadow/passwd_4.17.4-2_arm64.deb
Size: 1205920
MD5sum: 1d1b39b6716f5a1b5cf6dff903dd2cfb
SHA256: a6863055a3d53bf805dd0048b836e4b7a3d14a899b832a50d577b047da4202d8

Package: libsqlite3-0
Source: sqlite3
Version: 3.46.1-7+deb13u1
Installed-Size: 1854
Maintainer: Laszlo Boszormenyi (GCS) <gcs@debian.org>
Architecture: arm64
Depends: libc6 (>= 2.38)
Breaks: crowdsec (<< 1.4), python-migrate (<< 0.11.0-4~), python3-migrate (<< 0.11.0-4~)
Description: SQLite 3 shared library
Multi-Arch: same
Homepage: https://www.sqlite.org/
Description-md5: 701b171ca60f3c96227ee4783a35419c
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/s/sqlite3/libsqlite3-0_3.46.1-7+deb13u1_arm64.deb
Size: 853772
MD5sum: 246e57ede53f4571e43aff428649f2c7
SHA256: 69dd4b705eca29fe134c17f18aaa4cc9e7ecd01c0250d5e195358801514c76ac

Package: libsystemd0
Source: systemd
Version: 257.13-1~deb13u1
Installed-Size: 1276
Maintainer: Debian systemd Maintainers <pkg-systemd-maintainers@lists.alioth.debian.org>
Architecture: arm64
Depends: libc6 (>= 2.39), libcap2 (>= 1:2.10)
Recommends: libzstd1
Suggests: libgcrypt20, liblz4-1, liblzma5
Description: systemd utility library
Multi-Arch: same
Homepage: https://systemd.io
Description-md5: 9afc42993cf4bce87281097b6b9586c9
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/s/systemd/libsystemd0_257.13-1~deb13u1_arm64.deb
Size: 422500
MD5sum: f9b47dfbbe5af4fa7250661b0befb5ac
SHA256: d2f63c408549c29eaefb6f4c948393edce99f45ee8e43c991391bef63fb7d54b

Package: libudev1
Source: systemd
Version: 257.13-1~deb13u1
Installed-Size: 405
Maintainer: Debian systemd Maintainers <pkg-systemd-maintainers@lists.alioth.debian.org>
Architecture: arm64
Depends: libc6 (>= 2.38), libcap2 (>= 1:2.10)
Suggests: libidn2-0
Description: libudev shared library
Multi-Arch: same
Homepage: https://systemd.io
Description-md5: ee41de00cbb160aaa4ae2b193df34f91
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/s/systemd/libudev1_257.13-1~deb13u1_arm64.deb
Size: 144536
MD5sum: 44fbdd0dbaf630ce7ca8ab508f5ba1e7
SHA256: 459b1c9c17cc3c586e90d13d2a991715d498c6eed6c046cabf321021167bc380

Package: sysvinit-utils
Source: sysvinit
Version: 3.14-4
Essential: yes
Installed-Size: 196
Maintainer: Debian sysvinit maintainers <debian-init-diversity@chiark.greenend.org.uk>
Architecture: arm64
Replaces: lsb-base
Provides: lsb-base (= 11.1.0)
Depends: libc6 (>= 2.38)
Conflicts: lsb-base (<< 11.3~)
Description: System-V-like utilities
Multi-Arch: foreign
Homepage: https://github.com/slicer69/sysvinit
Description-md5: 41b1f0e27f14a0c053a574e35d1d7bd0
Tag: implemented-in::c, interface::commandline, role::program,
 scope::utility, use::analysing, use::checking, use::monitor,
 works-with::software:running
Section: admin
Priority: required
Filename: pool/main/s/sysvinit/sysvinit-utils_3.14-4_arm64.deb
Size: 33960
MD5sum: 150b8128ffe277ab81250b2cb64d5f6f
SHA256: 1366e87a382a6b95a0a7c507b8f1be9b193f772f3428f9f7e0271a217915cfae

Package: tar
Version: 1.35+dfsg-3.1
Essential: yes
Installed-Size: 3105
Maintainer: Janos Lenart <ocsi@debian.org>
Architecture: arm64
Replaces: cpio (<< 2.4.2-39)
Pre-Depends: libacl1 (>= 2.2.23), libc6 (>= 2.38), libselinux1 (>= 3.1~)
Suggests: bzip2, ncompress, xz-utils, tar-scripts, tar-doc
Conflicts: cpio (<= 2.4.2-38)
Breaks: dpkg-dev (<< 1.14.26)
Description: GNU version of the tar archiving utility
Multi-Arch: foreign
Homepage: https://www.gnu.org/software/tar/
Description-md5: 48033bf96442788d1f697785773ad9bb
Tag: admin::backup, admin::file-distribution, devel::packaging,
 implemented-in::c, interface::commandline, role::program,
 scope::utility, suite::gnu, use::storing, works-with-format::tar,
 works-with::archive, works-with::file
Section: utils
Priority: required
Filename: pool/main/t/tar/tar_1.35+dfsg-3.1_arm64.deb
Size: 802008
MD5sum: 091a5abbd79aa35881226017eb01f545
SHA256: c460a8294202ca3802b7c703b0bd70dd8bda1f2fc8785839595cf9d5e060d71b

Package: tzdata
Version: 2026b-0+deb13u1
Installed-Size: 1361
Maintainer: GNU Libc Maintainers <debian-glibc@lists.debian.org>
Architecture: all
Replaces: tzdata-legacy (= 2023c-8), tzdata-legacy (= 2023c-9)
Provides: tzdata-trixie
Depends: debconf (>= 0.5) | debconf-2.0
Breaks: tzdata-legacy (= 2023c-8), tzdata-legacy (= 2023c-9)
Description: time zone and daylight-saving time data
Multi-Arch: foreign
Homepage: https://www.iana.org/time-zones
Description-md5: a77a3cc9a67658dd7cfdc6547391b8f8
Tag: role::app-data, use::timekeeping
Section: localization
Priority: required
Filename: pool/main/t/tzdata/tzdata_2026b-0+deb13u1_all.deb
Size: 264316
MD5sum: 2508a8ecb1e7638d5723f3c1b2194243
SHA256: 306013b77311e875620e89f43c447765a6c72b7b398e0baa8e5721bcbbdf66cf

Package: usr-is-merged
Source: usrmerge
Version: 39+nmu2
Installed-Size: 16
Maintainer: Marco d'Itri <md@linux.it>
Architecture: all
Conflicts: acl (<< 2.2.52-3~), arptables (<< 0.0.4+snapshot20181021-1~), coreutils (<< 8.24-1~), cruft (<< 0.9.44~), cryptsetup (<< 2:1.7.0-1~), davfs2 (<< 1.5.2-1.2~), debianutils (<< 4.5~), dhcpcd (<< 1:5~), ebtables (<< 2.0.10.4+snapshot20181205-1~), elvis-tiny (<< 1.4-24~), kbd (<< 2.0.4-4~), ksh (<< 93u+20120801-3.1~), less (<< 481-2~), libbrlapi-dev (<< 5.3.1-1~), libc0.1 (<< 2.35-4), libc0.3 (<< 2.35-4), libc6 (<< 2.35-4), libc6.1 (<< 2.35-4), libdm0-dev, libjson-c-dev (<< 0.12.1-1.1~), libparted1.8-10 (<< 1.8.8.git.2008.03.24-11.2~), libpng12-0 (<< 1.2.54-6~), libusb-0.1-4 (<< 2:0.1.12-28~), lustre-utils (<< 1.8.5+dfsg-3.2~), mksh (<< 52b-1~), molly-guard (<< 0.7.1+exp1~), musl-dev (<< 1.1.9-1.1~), nano (<< 2.3.99pre3-1~), open-iscsi (<< 2.0.873+git0.3b4b4500-13~), open-vm-tools (<< 2:10.0.5-3227872-2~), policycoreutils (<< 2.4-4~), safe-rm (<< 0.12-6~), tcsh (<< 6.18.01-4~), vsearch (<< 1.9.5-2~), xfsdump (<< 3.1.6+nmu1~), xfslibs-dev (<< 4.9.0+nmu1~), yp-tools (<< 3.3-5~), zsh (<< 5.2-4~)
Breaks: cruft-ng (<< 0.4.4~), initramfs-tools (<< 0.121~)
Description: Transitional package to assert a merged-/usr system
Multi-Arch: foreign
Homepage: https://wiki.debian.org/UsrMerge
Description-md5: 4a472e7f5131656a998508f366c56c26
Tag: role::shared-lib
Section: oldlibs
Priority: optional
Filename: pool/main/u/usrmerge/usr-is-merged_39+nmu2_all.deb
Size: 5936
MD5sum: d0468d2b7eb9146e190f9890e2c0865c
SHA256: f152424b46eac53b30bd7c1df36726f86394e7026285d1672c0b0c1b5f99a632

Package: bsdutils
Source: util-linux (2.41-5)
Version: 1:2.41-5
Essential: yes
Installed-Size: 553
Maintainer: Chris Hofstaedtler <zeha@debian.org>
Architecture: arm64
Pre-Depends: libc6 (>= 2.38), libsystemd0 (>= 254)
Recommends: bsdextrautils
Description: basic utilities from 4.4BSD-Lite
Multi-Arch: foreign
Homepage: https://github.com/util-linux/util-linux
Description-md5: 07a52b485dd8e72c97ccec74c54564a0
Tag: admin::logging, implemented-in::c, interface::commandline,
 role::program, scope::utility, suite::bsd,
 works-with::software:running, works-with::text
Section: utils
Priority: required
Filename: pool/main/u/util-linux/bsdutils_2.41-5_arm64.deb
Size: 109072
MD5sum: 2d7ff5ae037279107fec5b3d4f2a7b5a
SHA256: a96b13485499f2c764b23fddac21397f79c104b4140838a8daebb1e6c6e8fec8

Package: libblkid1
Source: util-linux
Version: 2.41-5
Installed-Size: 518
Maintainer: Chris Hofstaedtler <zeha@debian.org>
Architecture: arm64
Depends: libc6 (>= 2.38)
Description: block device ID library
Multi-Arch: same
Homepage: https://github.com/util-linux/util-linux
Description-md5: a511054d3223ae05280eebc8f3e2c80e
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/u/util-linux/libblkid1_2.41-5_arm64.deb
Size: 165512
MD5sum: ed547c662079b0fa36ab72a9be20b2d0
SHA256: ee91c24cc227ed977c39d746d09532d357e66f147bd670be6cb69e2c3435cf76

Package: liblastlog2-2
Source: util-linux
Version: 2.41-5
Installed-Size: 124
Maintainer: Chris Hofstaedtler <zeha@debian.org>
Architecture: arm64
Depends: libc6 (>= 2.38), libsqlite3-0 (>= 3.5.9)
Description: lastlog2 database shared library
Multi-Arch: same
Homepage: https://github.com/util-linux/util-linux
Description-md5: eea62ff73e66a83c684042e59cac1a51
Section: libs
Priority: optional
Filename: pool/main/u/util-linux/liblastlog2-2_2.41-5_arm64.deb
Size: 28328
MD5sum: eb1428c138b601230ef5a280c4497985
SHA256: 2aabc6ee5bcd7867c4da38e8348f4a1ec6ca5ff4f37944dc7b056bd9f45156c7

Package: libmount1
Source: util-linux
Version: 2.41-5
Installed-Size: 589
Maintainer: Chris Hofstaedtler <zeha@debian.org>
Architecture: arm64
Depends: libblkid1 (= 2.41-5), libc6 (>= 2.38), libselinux1 (>= 3.1~)
Suggests: cryptsetup-bin
Description: device mounting library
Multi-Arch: same
Homepage: https://github.com/util-linux/util-linux
Description-md5: 7e446c44302ff94779f1434f2e05d553
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/u/util-linux/libmount1_2.41-5_arm64.deb
Size: 198880
MD5sum: aea7b6f86e8b7e3a6c474b0e40b85d36
SHA256: 2e791f8faf7bbbc94d354cf7b6c3728dc303b7aa327dfe7a2aa6954320977c8f

Package: libsmartcols1
Source: util-linux
Version: 2.41-5
Installed-Size: 457
Maintainer: Chris Hofstaedtler <zeha@debian.org>
Architecture: arm64
Depends: libc6 (>= 2.38)
Description: smart column output alignment library
Multi-Arch: same
Homepage: https://github.com/util-linux/util-linux
Description-md5: d5382f0223188fc45ed69f879488c198
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/u/util-linux/libsmartcols1_2.41-5_arm64.deb
Size: 138688
MD5sum: 1d499c4a8e535f70a12df2c8c8f0fff9
SHA256: ac0ae538118235b83de86121454488336a26767b612a34c2d387595cae3936e6

Package: libuuid1
Source: util-linux
Version: 2.41-5
Installed-Size: 126
Maintainer: Chris Hofstaedtler <zeha@debian.org>
Architecture: arm64
Replaces: libuuid1t64
Provides: libuuid1t64 (= 2.41-5)
Depends: libc6 (>= 2.38)
Suggests: uuid-runtime
Breaks: libuuid1t64 (<< 2.41-5)
Description: Universally Unique ID library
Multi-Arch: same
Homepage: https://github.com/util-linux/util-linux
Description-md5: f31dd3d34d42a99fedd60c9fb7d79469
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/u/util-linux/libuuid1_2.41-5_arm64.deb
Size: 37412
MD5sum: b7f253331894ce1e6919c54f54f43452
SHA256: 85b7d04605f9d423d5699b3c07f027e8380649fd164a51e0712cded0cc56eced

Package: login
Source: util-linux (2.41-5)
Version: 1:4.16.0-2+really2.41-5
Installed-Size: 416
Maintainer: Chris Hofstaedtler <zeha@debian.org>
Architecture: arm64
Depends: libpam-modules, libpam-runtime, libaudit1 (>= 1:2.2.1), libc6 (>= 2.38), libcrypt1 (>= 1:4.1.0), libpam0g (>= 0.99.7.1)
Pre-Depends: login.defs (>= 1:4.16.0-1~)
Description: system login tools
Multi-Arch: foreign
Homepage: https://github.com/util-linux/util-linux
Description-md5: 5df4e54496497c63114f4af2c9648682
Protected: yes
Tag: admin::login, implemented-in::c, interface::commandline,
 interface::text-mode, role::program, security::authentication,
 use::login
Section: admin
Priority: required
Filename: pool/main/u/util-linux/login_4.16.0-2+really2.41-5_arm64.deb
Size: 113812
MD5sum: 184e0752ec4f3f64e37dbdc794fba78b
SHA256: c58d89c3e776ec356d75f33bbf5d24a797050691edaee4d97d31da9e6e47b2ec

Package: mount
Source: util-linux
Version: 2.41-5
Installed-Size: 656
Maintainer: Chris Hofstaedtler <zeha@debian.org>
Architecture: arm64
Pre-Depends: libblkid1 (= 2.41-5), libc6 (>= 2.38), libmount1 (= 2.41-5), libselinux1 (>= 3.1~), libsmartcols1 (= 2.41-5)
Suggests: nfs-common
Description: tools for mounting and manipulating filesystems
Multi-Arch: foreign
Homepage: https://github.com/util-linux/util-linux
Description-md5: 90e187380786a435bb9c85b37afa2c5f
Tag: admin::filesystem, hardware::storage, implemented-in::c,
 interface::commandline, role::program, scope::utility
Section: admin
Priority: required
Filename: pool/main/u/util-linux/mount_2.41-5_arm64.deb
Size: 160584
MD5sum: 1a977dd1e9e38bc2b91ee65435c4d7bc
SHA256: 62fd64b4fc9bac520d37eb7b2bee0fa310bbecf66d83d5ef5989092ecd0669b6

Package: util-linux
Version: 2.41-5
Essential: yes
Installed-Size: 7453
Maintainer: Chris Hofstaedtler <zeha@debian.org>
Architecture: arm64
Replaces: hardlink
Provides: hardlink
Pre-Depends: libpam-modules, libpam-runtime, libblkid1 (= 2.41-5), libc6 (>= 2.41), libcap-ng0 (>= 0.7.9), libcrypt1 (>= 1:4.1.0), liblastlog2-2 (= 2.41-5), libmount1 (= 2.41-5), libpam0g (>= 0.99.7.1), libselinux1 (>= 3.1~), libsmartcols1 (= 2.41-5), libsystemd0, libtinfo6 (>= 6), libudev1 (>= 183), libuuid1 (= 2.41-5)
Recommends: sensible-utils
Suggests: dosfstools, kbd, util-linux-extra, util-linux-locales, wtmpdb
Conflicts: hardlink
Description: miscellaneous system utilities
Multi-Arch: foreign
Homepage: https://github.com/util-linux/util-linux
Description-md5: 33d0291a69ffd307cfba295915df602a
Tag: admin::configuring, admin::filesystem, admin::hardware,
 implemented-in::c, interface::commandline, interface::text-mode,
 role::program, scope::utility, uitoolkit::ncurses
Section: utils
Priority: required
Filename: pool/main/u/util-linux/util-linux_2.41-5_arm64.deb
Size: 1191312
MD5sum: 20fffec1635d1604b9fd118e47643aa5
SHA256: d336901e4c1aedf268837d41a4e1004faa9b7ad4f1e6763f01711b487a4c18ce

Package: libxxhash0
Source: xxhash
Version: 0.8.3-2
Installed-Size: 88
Maintainer: Josue Ortega <josue@debian.org>
Architecture: arm64
Depends: libc6 (>= 2.17)
Description: shared library for xxhash
Multi-Arch: same
Homepage: https://cyan4973.github.io/xxHash
Description-md5: 6bd5146e7d8c018b3155cacf897b1233
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/x/xxhash/libxxhash0_0.8.3-2_arm64.deb
Size: 22276
MD5sum: 5dc2a4bc2ece3aab055ac8e75a93d97c
SHA256: 66665776740ced6c5ddcb48a89a92b056d0dcb13851e1ff573d2b99b766e5c6c

Package: liblzma5
Source: xz-utils
Version: 5.8.1-1+deb13u1
Installed-Size: 435
Maintainer: Sebastian Andrzej Siewior <sebastian@breakpoint.cc>
Architecture: arm64
Depends: libc6 (>= 2.34)
Breaks: liblzma2 (<< 5.1.1alpha+20110809-3~)
Description: XZ-format compression library
Multi-Arch: same
Homepage: https://tukaani.org/xz/
Description-md5: 0ceca09eb4ab99863be3578fa55e7d2b
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/x/xz-utils/liblzma5_5.8.1-1+deb13u1_arm64.deb
Size: 302720
MD5sum: 442ae1a890e7c5eb5d63a82bed5bff98
SHA256: 6a7b98005f84fc26d66a45e432f70d0793646a734f8c44cd1626b3ffa8edd7c6

Package: zlib1g
Source: zlib (1:1.3.dfsg+really1.3.1-1)
Version: 1:1.3.dfsg+really1.3.1-1+b1
Installed-Size: 181
Maintainer: Mark Brown <broonie@debian.org>
Architecture: arm64
Provides: libz1
Depends: libc6 (>= 2.17)
Conflicts: zlib1 (<= 1:1.0.4-7)
Breaks: libxml2 (<< 2.7.6.dfsg-2), texlive-binaries (<< 2023.20230311.66589-8)
Description: compression library - runtime
Multi-Arch: same
Homepage: http://zlib.net/
Description-md5: 567f396aeeb2b2b63295099aed237057
Tag: role::shared-lib
Section: libs
Priority: optional
Filename: pool/main/z/zlib/zlib1g_1.3.dfsg+really1.3.1-1+b1_arm64.deb
Size: 85116
MD5sum: 47323069c3fef2f10ccdff2be39964f1
SHA256: 209aa5cf671e97b9eb0410844fa6df4cae2e75b0c72e7802ab6c8ece13e6ddef

