package com.bipros.api.config;

import com.bipros.admin.domain.model.Currency;
import com.bipros.admin.domain.model.GlobalSetting;
import com.bipros.admin.domain.repository.CurrencyRepository;
import com.bipros.admin.domain.repository.GlobalSettingRepository;
import com.bipros.calendar.domain.model.Calendar;
import com.bipros.calendar.domain.model.CalendarType;
import com.bipros.calendar.domain.model.CalendarWorkWeek;
import com.bipros.calendar.domain.model.DayType;
import com.bipros.calendar.domain.repository.CalendarRepository;
import com.bipros.calendar.domain.repository.CalendarWorkWeekRepository;
import com.bipros.security.domain.model.Role;
import com.bipros.security.domain.model.User;
import com.bipros.security.domain.model.UserRole;
import com.bipros.security.domain.repository.RoleRepository;
import com.bipros.security.domain.repository.UserRepository;
import com.bipros.security.domain.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;

@Slf4j
@Component
@Profile({"dev", "seed"})
@Order(100)
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

  private final RoleRepository roleRepository;
  private final UserRepository userRepository;
  private final UserRoleRepository userRoleRepository;
  private final CalendarRepository calendarRepository;
  private final CalendarWorkWeekRepository calendarWorkWeekRepository;
  private final CurrencyRepository currencyRepository;
  private final GlobalSettingRepository globalSettingRepository;
  private final PasswordEncoder passwordEncoder;
  private final ProfileSeeder profileSeeder;
  private final com.bipros.security.domain.repository.ProfileRepository profileRepository;

  @Value("${bipros.admin.username:admin}")
  private String adminUsername;

  @Value("${bipros.admin.password:admin123}")
  private String adminPassword;

  @Value("${bipros.admin.email:admin@bipros.local}")
  private String adminEmail;

  @Override
  @Transactional
  public void run(String... args) throws Exception {
    // Roles are seeded idempotently every boot so newly-introduced roles (EXECUTIVE, PMO,
    // FINANCE, TEAM_MEMBER, CLIENT) appear on existing dev databases without a wipe.
    seedRoles();

    // Profiles seeded idempotently every boot so newly-introduced defaults appear without a wipe.
    profileSeeder.seed();

    if (roleRepository.count() > 0 && userRepository.findByUsername(adminUsername).isPresent()) {
      log.info("Bulk data already seeded, skipping calendar/currency/settings");
      ensureAdminHasAdminRole();
      ensureAdminHasSystemAdminProfile();
      seedDemoUsers();
      return;
    }

    log.info("Starting data seeding...");
    seedAdminUser();
    seedDemoUsers();
    seedGlobalCalendar();
    seedBaseCurrency();
    seedGlobalSettings();
    log.info("Data seeding completed successfully");
  }

  /**
   * Idempotent role catalogue. Each role is created only if absent. Includes:
   * <ul>
   *   <li>Original platform roles: ADMIN, PROJECT_MANAGER, SCHEDULER, RESOURCE_MANAGER, VIEWER</li>
   *   <li>RBAC+ABAC rollout additions: EXECUTIVE, PMO, FINANCE, TEAM_MEMBER, CLIENT</li>
   *   <li>RBAC Phase 0.1 canonical names: SAFETY_OFFICER (was HSE_OFFICER),
   *       QA_QC_ENGINEER (was QC_MANAGER), plus PLANNING_ENGINEER, SUPERVISOR,
   *       STORE_MANAGER, PROCUREMENT_OFFICER, CONTRACTOR.</li>
   * </ul>
   * Existing dev databases pick up the renames via Liquibase changelog 089;
   * this seeder only inserts missing names on a fresh boot.
   */
  private void seedRoles() {
    String[][] roles = {
      {"ADMIN", "Administrator with full system access"},
      {"EXECUTIVE", "Read-only access across portfolios; sees roll-ups and KPIs"},
      {"PMO", "Project management office; configures methodology and governance"},
      {"FINANCE", "May view and edit cost / contract value / payment fields"},
      {"PROJECT_MANAGER", "Project Manager with project control"},
      {"SCHEDULER", "Owns activity / relationship / schedule editing"},
      {"RESOURCE_MANAGER", "Manages resource pool and assignments"},
      {"TEAM_MEMBER", "Activity-level executor; updates assigned activities only"},
      {"CLIENT", "Read-only access scoped to projects they own"},
      {"VIEWER", "Viewer with read-only access"},
      {"FOREMAN", "Foreman; raises Permit-to-Work applications"},
      {"SITE_ENGINEER", "Site Engineer; reviews permits in their assigned zones"},
      {"SAFETY_OFFICER", "Safety / HSE officer"},
      {"SITE_MANAGER", "Site Manager; owns daily execution, crew & machine deployment"},
      {"PROJECT_ENGINEER", "Project Engineer; bridges design and execution, technical sign-off"},
      {"QA_QC_ENGINEER", "Quality assurance / quality control engineer"},
      {"BIM_DATA_COORDINATOR", "BIM / Data Coordinator; data integrity and model linkage"},
      {"PLANNING_ENGINEER", "Schedule & planning engineer"},
      {"SUPERVISOR", "Labor supervision"},
      {"STORE_MANAGER", "Inventory and material store management"},
      {"PROCUREMENT_OFFICER", "Material purchasing"},
      {"CONTRACTOR", "External work execution contractor"}
    };
    int created = 0;
    for (String[] r : roles) {
      if (roleRepository.findByName(r[0]).isEmpty()) {
        roleRepository.save(new Role(r[0], r[1]));
        log.debug("Created role: {}", r[0]);
        created++;
      }
    }
    if (created > 0) {
      log.info("Seeded {} new roles (existing roles untouched)", created);
    }
  }

  private void seedAdminUser() {
    log.debug("Seeding admin user");

    // Create admin user
    User adminUser = new User(adminUsername, adminEmail, passwordEncoder.encode(adminPassword));
    adminUser.setFirstName("System");
    adminUser.setLastName("Administrator");
    adminUser.setEnabled(true);
    adminUser.setAccountLocked(false);

    profileRepository.findByCode("SYSTEM_ADMIN").ifPresent(p -> adminUser.setProfileId(p.getId()));
    User savedUser = userRepository.save(adminUser);
    log.debug("Created admin user: {}", savedUser.getUsername());

    assignAdminRole(savedUser);
    log.info("Seeded admin user with ADMIN role");
  }

  /** Idempotent: ensure the seeded admin user is linked to the SYSTEM_ADMIN profile. */
  private void ensureAdminHasSystemAdminProfile() {
    profileRepository
        .findByCode("SYSTEM_ADMIN")
        .ifPresent(profile ->
            userRepository
                .findByUsername(adminUsername)
                .filter(u -> u.getProfileId() == null)
                .ifPresent(u -> {
                  u.setProfileId(profile.getId());
                  userRepository.save(u);
                  log.info("Self-heal: linked SYSTEM_ADMIN profile to '{}'", adminUsername);
                }));
  }

  /**
   * Seeds two demo users in addition to admin so the platform ships with one account per
   * representative profile (Project Manager, Scheduler). Idempotent — skips if the username
   * already exists. Together with the admin user this gives 3 demo accounts total.
   */
  private void seedDemoUsers() {
    seedDemoUser("pmanager", "manager@bipros.local", "manager123",
        "Priya", "Manager", "PROJECT_MANAGER");
    seedDemoUser("scheduler", "scheduler@bipros.local", "scheduler123",
        "Sam", "Scheduler", "SCHEDULER");
  }

  private void seedDemoUser(String username, String email, String password,
                            String firstName, String lastName, String profileCode) {
    if (userRepository.findByUsername(username).isPresent()) return;

    User user = new User(username, email, passwordEncoder.encode(password));
    user.setFirstName(firstName);
    user.setLastName(lastName);
    user.setEnabled(true);

    com.bipros.security.domain.model.Profile profile =
        profileRepository.findByCode(profileCode).orElse(null);
    if (profile != null) user.setProfileId(profile.getId());

    User saved = userRepository.save(user);

    if (profile != null) {
      Role role = roleRepository.findByName(profile.getLegacyRoleName()).orElse(null);
      if (role != null
          && !userRoleRepository.existsByUserIdAndRoleId(saved.getId(), role.getId())) {
        userRoleRepository.save(new UserRole(saved.getId(), role.getId()));
      }
    }
    log.info("Seeded demo user '{}' on profile {}", username, profileCode);
  }

  /**
   * Idempotent: ensures the seeded admin user exists and has the ADMIN role linked via
   * {@code user_roles}. Safe to call on every startup even if the user/role already exist.
   */
  private void ensureAdminHasAdminRole() {
    userRepository
        .findByUsername(adminUsername)
        .ifPresent(
            adminUser -> {
              Role adminRole = roleRepository.findByName("ADMIN").orElse(null);
              if (adminRole == null) {
                log.warn("ADMIN role missing while attempting to self-heal admin user mapping");
                return;
              }
              if (!userRoleRepository.existsByUserIdAndRoleId(adminUser.getId(), adminRole.getId())) {
                UserRole userRole = new UserRole(adminUser.getId(), adminRole.getId());
                userRoleRepository.save(userRole);
                log.info("Self-heal: linked ADMIN role to existing admin user '{}'", adminUsername);
              }
            });
  }

  private void assignAdminRole(User adminUser) {
    Role adminRole =
        roleRepository
            .findByName("ADMIN")
            .orElseThrow(() -> new RuntimeException("ADMIN role not found"));

    if (userRoleRepository.existsByUserIdAndRoleId(adminUser.getId(), adminRole.getId())) {
      return;
    }

    // Persist UserRole explicitly via its own repository — the @OneToMany on User.roles
    // is not cascaded, so saving the User alone will NOT insert the user_roles row.
    UserRole userRole = new UserRole(adminUser.getId(), adminRole.getId());
    userRoleRepository.save(userRole);
    adminUser.getRoles().add(userRole);
  }

  private void seedGlobalCalendar() {
    log.debug("Seeding global calendar");

    Calendar globalCalendar =
        Calendar.builder()
            .name("Standard")
            .description("Default global calendar with 5-day work week")
            .calendarType(CalendarType.GLOBAL)
            .isDefault(true)
            .standardWorkHoursPerDay(8.0)
            .standardWorkDaysPerWeek(5)
            .build();

    Calendar savedCalendar = calendarRepository.save(globalCalendar);
    log.info("Created global calendar: Standard");

    // Create CalendarWorkWeek entries for 7 days
    // Monday-Friday: WORKING days with 8 work hours
    DayOfWeek[] workDays = {
      DayOfWeek.MONDAY,
      DayOfWeek.TUESDAY,
      DayOfWeek.WEDNESDAY,
      DayOfWeek.THURSDAY,
      DayOfWeek.FRIDAY
    };

    for (DayOfWeek day : workDays) {
      CalendarWorkWeek workWeek =
          CalendarWorkWeek.builder()
              .calendarId(savedCalendar.getId())
              .dayOfWeek(day)
              .dayType(DayType.WORKING)
              .startTime1(LocalTime.of(8, 0))
              .endTime1(LocalTime.of(12, 0))
              .startTime2(LocalTime.of(13, 0))
              .endTime2(LocalTime.of(17, 0))
              .totalWorkHours(8.0)
              .build();
      calendarWorkWeekRepository.save(workWeek);
      log.debug("Created work week entry for {}", day);
    }

    // Saturday-Sunday: NON_WORKING days
    DayOfWeek[] weekendDays = {DayOfWeek.SATURDAY, DayOfWeek.SUNDAY};

    for (DayOfWeek day : weekendDays) {
      CalendarWorkWeek nonWorkWeek =
          CalendarWorkWeek.builder()
              .calendarId(savedCalendar.getId())
              .dayOfWeek(day)
              .dayType(DayType.NON_WORKING)
              .totalWorkHours(0.0)
              .build();
      calendarWorkWeekRepository.save(nonWorkWeek);
      log.debug("Created non-work week entry for {}", day);
    }

    log.info("Created 7 CalendarWorkWeek entries for Standard calendar");
  }

  private void seedBaseCurrency() {
    log.debug("Seeding base currency");

    // INR as base (Indian market: IOCL, DMIC, government tenders). USD also
    // loaded as a non-base currency so FX-denominated contracts have an option.
    Currency inr =
        new Currency("INR", "Indian Rupee", "₹", BigDecimal.ONE, true, 2);
    currencyRepository.save(inr);

    Currency usd =
        new Currency("USD", "United States Dollar", "$", BigDecimal.valueOf(0.012), false, 2);
    currencyRepository.save(usd);

    log.debug("Created base currency: INR (+ USD as secondary)");
  }

  private void seedGlobalSettings() {
    log.debug("Seeding global settings");

    GlobalSetting schedulingOption =
        new GlobalSetting(
            "default.scheduling.option",
            "RETAINED_LOGIC",
            "Default scheduling option for projects",
            "scheduling");

    GlobalSetting evmTechnique =
        new GlobalSetting(
            "default.evm.technique",
            "ACTIVITY_PERCENT_COMPLETE",
            "Default EVM technique for projects",
            "evm");

    globalSettingRepository.save(schedulingOption);
    globalSettingRepository.save(evmTechnique);

    log.debug("Created global settings");
  }
}
