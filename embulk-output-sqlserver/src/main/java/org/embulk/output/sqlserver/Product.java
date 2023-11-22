package org.embulk.output.sqlserver;

import java.util.Locale;

import org.embulk.config.ConfigException;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Product
{
    // https://learn.microsoft.com/en-us/sql/sql-server/sql-docs-navigation-guide?view=sql-server-ver16#applies-to
    SQL_SERVER,
    AZURE_SYNAPSE_ANALYTICS;

    @JsonValue
    @Override
    public String toString()
    {
        return name().toLowerCase(Locale.ENGLISH);
    }

    @JsonCreator
    public static Product fromString(String value)
    {
        for (Product product : Product.values()) {
            if (product.toString().equals(value)) {
                return product;
            }
        }
        throw new ConfigException(String.format("Unknown product '%s'.", value));
    }
}
