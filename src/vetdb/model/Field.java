package vetdb.model;

/**
 * Every clinic attribute we collect, in the order they appear in the exports.
 * The column name is the lowercase enum name.
 */
public enum Field {
    NAME,
    CATEGORY,
    STREET,
    SUBURB,
    STATE,
    POSTCODE,
    LATITUDE,
    LONGITUDE,
    WEBSITE,
    PHONE,
    EMAIL,
    FACEBOOK,
    INSTAGRAM,
    OTHER_SOCIALS,
    SPECIES_TREATED,
    HOME_MOBILE_VISITS,
    SERVICES,
    OPENING_HOURS,
    GOOGLE_REVIEW_COUNT,
    GOOGLE_RATING,
    DIRECTORY_RATING,
    AFTER_HOURS_EMERGENCY,
    NUMBER_OF_VETS,
    NUMBER_OF_STAFF,
    BOOK_ONLINE,
    BOOKING_URL,
    TELEHEALTH,
    PARASITE_PILOT,
    PAYMENT_OPTIONS,
    DIGITAL_PAYMENT,
    PAYMENT_PLAN,
    WELLNESS_PLAN,
    ABN,
    REGISTERED_NAME,
    ABN_STATUS,
    ABN_REGISTERED_FROM,
    GOOGLE_MAPS_URL,
    GOOGLE_BUSINESS_STATUS;

    public String column() {
        return name().toLowerCase();
    }

    /** Fields holding Y or N (or blank when no source says). */
    public boolean isYesNo() {
        return switch (this) {
            case HOME_MOBILE_VISITS, AFTER_HOURS_EMERGENCY, BOOK_ONLINE, TELEHEALTH, PARASITE_PILOT,
                 DIGITAL_PAYMENT, PAYMENT_PLAN, WELLNESS_PLAN -> true;
            default -> false;
        };
    }

    /** Fields holding a "; " separated list. */
    public boolean isList() {
        return switch (this) {
            case SERVICES, OTHER_SOCIALS, PAYMENT_OPTIONS -> true;
            default -> false;
        };
    }
}
