package com.example;



import org.zeromq.ZContext;

import org.zeromq.ZMQ;

import org.zeromq.SocketType;



import java.sql.*;

import java.util.concurrent.ExecutorService;

import java.util.concurrent.Executors;

import java.util.ArrayList;



public class BackupCentralServer {



    private static final ExecutorService pool = Executors.newFixedThreadPool(10);



    public static void main(String[] args) {

        System.out.println("Iniciando Servidor Backup Worker...");



        // Verificar conexión a la base de datos antes de continuar

        try (Connection testConn = ConexionDB.conectar()) {

            if (testConn == null || testConn.isClosed()) {

                System.err.println("No se pudo conectar a la base de datos. Terminando el servidor.");

                return;

            } else {

                System.out.println("Conexión a la base de datos verificada correctamente.");

            }

        } catch (SQLException e) {

            System.err.println("Error al conectar a la base de datos: " + e.getMessage());

            return;

        }



        try (ZContext context = new ZContext()) {

            ZMQ.Socket worker = context.createSocket(SocketType.DEALER);

            worker.bind("tcp://*:5556");



            

        // Hilo de healthcheck que responde "PONG" a "PING" en puerto 6000

        new Thread(() -> {

            try (ZContext healthContext = new ZContext()) {

                ZMQ.Socket responder = context.createSocket(SocketType.REP);

                responder.bind("tcp://*:6000");

                System.out.println("HealthCheck REP activo en puerto 6000...");

                while (!Thread.currentThread().isInterrupted()) {

                    String msg = responder.recvStr();

                    if ("PING".equals(msg)) {

                        responder.send("PONG");

                    } else {

                        responder.send("UNKNOWN");

                    }

                }

            }

        }).start();





            System.out.println("Worker conectado al broker. Esperando solicitudes...");



            while (!Thread.currentThread().isInterrupted()) {

                byte[] identidad = worker.recv(0);

                worker.recv(0); // frame vacío

                String mensaje = worker.recvStr(0);



                pool.execute(() -> {

                    String respuesta = procesarSolicitud(mensaje);

                    worker.send(identidad, ZMQ.SNDMORE);

                    worker.send("", ZMQ.SNDMORE);

                    worker.send(respuesta);

                });

            }

        }

    }



	private static String procesarSolicitud(String data) {

	    try {

		// Ahora esperamos: requestId,semestre,facultad,programa,cantSalones,cantLabs

		String[] partes = data.split(",");

		if (partes.length != 6) {

		    return "Error: Formato de solicitud inválido. Se esperan 6 campos.";

		}

		

		String requestId = partes[0];     // Nuevo: requestId

		String semestre = partes[1];      // era partes[0]

		String facultad = partes[2];      // era partes[1]

		String programa = partes[3];      // era partes[2]

		int cantSalones = Integer.parseInt(partes[4]); // era partes[3]

		int cantLabs = Integer.parseInt(partes[5]);    // era partes[4]



		if(!validacionData(semestre, facultad, programa, cantSalones, cantLabs)){

		    return requestId + ",Error: Los datos ingresados en la solicitud son inválidos!";

		}



		Connection conn = ConexionDB.conectar();



		int salonesDisponibles = contarAulas(conn, "Salon", semestre, "Disponible");

		int laboratoriosDisponibles = contarAulas(conn, "Laboratorio", semestre, "Disponible");



		boolean asignadoSalones = salonesDisponibles >= cantSalones;

		boolean asignadoLabs = laboratoriosDisponibles >= cantLabs;



		if (asignadoSalones) {

		    asignarAulas(conn, programa, "Salon", cantSalones);

		}



		if (!asignadoLabs && (salonesDisponibles - cantSalones) >= (cantLabs - laboratoriosDisponibles)) {

		    asignarAulas(conn, programa, "Laboratorio", laboratoriosDisponibles);

		    asignarAulas(conn, programa, "Salon", cantLabs - laboratoriosDisponibles);

		    asignadoLabs = true;

		} else if (asignadoLabs) {

		    asignarAulas(conn, programa, "Laboratorio", cantLabs);

		}



		String status;

		if (asignadoSalones && asignadoLabs) {

		    status = "Aprobada";

		} else {

		    System.err.println("⚠️ ALERTA: No hay suficientes aulas para " + programa + " en " + semestre);

		    status = "Denegada";

		}



		insertarSolicitud(conn, semestre, facultad, programa, cantSalones, cantLabs, status);

		conn.close();

		

		// Incluir requestId en la respuesta para correlación

		return requestId + ",Resultado: " + status;

		

	    } catch (Exception e) {

		e.printStackTrace();

		return "Error,Error procesando solicitud: " + e.getMessage();

	    }

	}



// Método de validación actualizado para recibir parámetros individuales

private static boolean validacionData(String semestre, String facultad, String programa, int cantSalones, int cantLabs){

    try{

        Connection conn = ConexionDB.conectar();



        // Validar semestre

        if(!semestre.equals("2025-10") && !semestre.equals("2025-20")){

            throw new Exception("Semestre ingresado inválido");

        }



        // Validar facultad

        String sql = "SELECT id FROM Facultad WHERE nombre = ?";

        try(PreparedStatement ps = conn.prepareStatement(sql)){

            ps.setString(1, facultad);

            ResultSet rs = ps.executeQuery();

            if(!rs.next()){

                throw new Exception("La facultad ingresada no existe");

            }

        }



        // Validar programa

        sql = "SELECT id FROM Programa WHERE nombre = ?";

        try(PreparedStatement ps = conn.prepareStatement(sql)){

            ps.setString(1, programa);

            ResultSet rs = ps.executeQuery();

            if(!rs.next()){

                throw new Exception("El Programa ingresado no existe");

            }

        }



        // Validar cantidades

        if(cantSalones < 0 || cantLabs < 0){

            throw new Exception("Cantidad de Salones o Laboratorios inválida");

        }



        conn.close();

        return true;



    }catch(Exception e){

        System.out.println("Error procesando solicitud: " + e.getMessage());

        return false;

    }

}



    private static int contarAulas(Connection conn, String tipo, String semestre, String estado) throws SQLException {

        String sql = "SELECT COUNT(*) FROM Aulas a WHERE a.tipo = ? AND a.status = ? AND a.semestre = ? AND a.programa_id IS NULL";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, tipo);

            ps.setString(2, estado);

            ps.setString(3, semestre);

            ResultSet rs = ps.executeQuery();

            return rs.next() ? rs.getInt(1) : 0;

        }

    }



    private static void asignarAulas(Connection conn, String programa, String tipo, int cantidad) throws SQLException {

        int program_id = 0;



    // Paso 1: Obtener ID del programa

    String program_id_query = "SELECT id FROM Programa WHERE nombre = ?";

    try (PreparedStatement ps = conn.prepareStatement(program_id_query)) {

        ps.setString(1, programa);

        ResultSet rs = ps.executeQuery();

        if (rs.next()) {

            program_id = rs.getInt(1);

        }

        rs.close();

    }



    // Paso 2: Obtener IDs de aulas disponibles

    ArrayList<Integer> idsDisponibles = new ArrayList<>();

    String status_query = "SELECT id FROM Aulas WHERE status = 'Disponible' AND tipo = ? LIMIT ?";

    try (PreparedStatement stmt = conn.prepareStatement(status_query)) {

            stmt.setString(1, tipo);

            stmt.setInt(2, cantidad);

            ResultSet rs_status = stmt.executeQuery();

        while (rs_status.next()) {

            idsDisponibles.add(rs_status.getInt("id"));

        }

    }



    // Paso 3: Actualizar aulas individualmente

    String update_query = "UPDATE Aulas SET status = ?, programa_id = ? WHERE id = ?";

    try (PreparedStatement updateStmt = conn.prepareStatement(update_query)) {

        for (int id : idsDisponibles) {

            updateStmt.setString(1, "Ocupado");

            updateStmt.setInt(2, program_id);

            updateStmt.setInt(3, id);

            updateStmt.executeUpdate();

        }

    }

    }



    private static void insertarSolicitud(Connection conn, String semestre, String facultad, String programa,

                                          int cantSalones, int cantLabs, String status) throws SQLException {



        String sql = "INSERT INTO Solicitud (semestre, facultad_id, programa_id, cant_salon, cant_lab, status) " +

                     "VALUES (?, " +

                     "(SELECT id FROM Facultad WHERE nombre = ?), " +

                     "(SELECT id FROM Programa WHERE nombre = ?), ?, ?, ?)";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, semestre);

            ps.setString(2, facultad);

            ps.setString(3, programa);

            ps.setInt(4, cantSalones);

            ps.setInt(5, cantLabs);

            ps.setString(6, status);

            ps.executeUpdate();

        }

    }



    private static boolean validacionData(String data){

        try{

            Connection conn = ConexionDB.conectar();



        String[] partes = data.split(",");

            String semestre = partes[0];

            if(!semestre.equals("2025-10") && !semestre.equals("2025-10")){

                throw new Exception("Semestre ingresado invalido");

            }



            String facultad = partes[1];

            String sql = "SELECT id FROM Facultad WHERE nombre = ?";

            try(PreparedStatement ps = conn.prepareStatement(sql)){

                ps.setString(1, facultad);

                ResultSet rs = ps.executeQuery();

                if(!rs.next()){

                    throw new Exception("La facultad ingresada no existe");

                }

            }catch(Exception e){

                e.printStackTrace();

                System.out.println("Error procesando solicitud: " + e.getMessage());

                return false;

            }



            String programa = partes[2];

            sql = "SELECT id FROM Programa WHERE nombre = ?";

            try(PreparedStatement ps = conn.prepareStatement(sql)){

                ps.setString(1, programa);

                ResultSet rs = ps.executeQuery();

                if(!rs.next()){

                    throw new Exception("El Programa ingresado no existe");

                }

            }catch(Exception e){

                e.printStackTrace();

                System.out.println("Error procesando solicitud: " + e.getMessage());

                return false;

            }





            int cantSalones = Integer.parseInt(partes[3]);

            int cantLabs = Integer.parseInt(partes[4]);



            if(cantSalones < 0 || cantLabs < 0){

                throw new Exception("Cantidad de Salones o Laboratorios invalida");

            }



            conn.close();



            }catch(Exception e){

                System.out.println("Error procesando solicitud: " + e.getMessage());

                return false;

            }



            



            return true;



    }

}


