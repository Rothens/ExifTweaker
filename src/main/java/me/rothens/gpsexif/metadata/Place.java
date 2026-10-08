package me.rothens.gpsexif.metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;

/**
 * Where a photo was taken, in words: the IPTC location fields that photo tools show (Lightroom, digiKam, photo
 * sites). Blank parts are {@code null}.
 *
 * @param sublocation a landmark or part of the city, e.g. "Tihany Abbey"
 * @param city        city, town or village
 * @param state       state, province or county
 * @param country     country name
 * @param countryCode ISO 3166-1 alpha-2 code, upper case, e.g. "HU"
 */
public record Place(String sublocation, String city, String state, String country, String countryCode) {

    /** A position without a place, e.g. out at sea. Writing it removes the place fields. */
    public static final Place NONE = new Place(null, null, null, null, null);

    public Place {
        sublocation = clean(sublocation);
        city = clean(city);
        state = clean(state);
        country = clean(country);
        countryCode = null == clean(countryCode) ? null : clean(countryCode).toUpperCase(Locale.ROOT);
    }

    private static String clean(String s) {
        return null == s || s.isBlank() ? null : s.strip();
    }

    public boolean isEmpty() {
        return null == sublocation && null == city && null == state && null == country && null == countryCode;
    }

    /** E.g. "Tihany Abbey, Tihany, Veszprém County, Hungary"; empty if there's no place. */
    public String label() {
        return join(sublocation, city, state, country);
    }

    /** E.g. "Tihany, Hungary": the most specific of city / state / sublocation, and the country. */
    public String shortLabel() {
        String local = null != city ? city : null != sublocation ? sublocation : state;
        return join(local, country);
    }

    /** The ISO 3166-1 alpha-3 code IPTC IIM uses ("HUN"), or {@code null}. */
    public String countryCode3() {
        if (null == countryCode) {
            return null;
        }
        if (countryCode.length() == 3) {
            return countryCode;
        }
        try {
            String code = new Locale("", countryCode).getISO3Country();
            return code.isEmpty() ? null : code;
        } catch (MissingResourceException e) {
            return null;
        }
    }

    /** An ISO 3166-1 alpha-3 code as alpha-2 ("HUN" -> "HU"), or the code as it is if unknown. */
    public static String alpha2(String code) {
        if (null == code || code.strip().length() != 3) {
            return code;
        }
        for (String country : Locale.getISOCountries()) {
            try {
                if (new Locale("", country).getISO3Country().equalsIgnoreCase(code.strip())) {
                    return country;
                }
            } catch (MissingResourceException ignored) {
                // no alpha-3 code for this one
            }
        }
        return code;
    }

    private static String join(String... parts) {
        List<String> shown = new ArrayList<>();
        for (String part : parts) {
            if (null != part && !shown.contains(part)) {
                shown.add(part);
            }
        }
        return String.join(", ", shown);
    }
}
