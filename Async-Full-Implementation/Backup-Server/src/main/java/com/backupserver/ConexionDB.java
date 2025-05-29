package com.backupserver;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class ConexionDB {

    private static final String DB_URL = "jdbc:mysql://localhost:3306/DistribuidosDB";
    private static final String USER = "host2"; 
    private static final String PASSWORD = "12345678"; 

    public static Connection conectar() {
        try {
            return DriverManager.getConnection(DB_URL, USER, PASSWORD);
        } catch (SQLException e) {
            System.err.println("Error al conectar con la base de datos: " + e.getMessage());
            return null;
        }
    }
}
